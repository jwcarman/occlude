/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.occlude.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.occlude.AuditRecord;
import org.jwcarman.occlude.Lineage;
import org.jwcarman.occlude.Storage;
import org.jwcarman.occlude.StoredMetadata;
import org.jwcarman.occlude.StoredValue;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Label;

/**
 * Storage in a database, with every payload encrypted.
 *
 * <p>A value is serialised by whatever {@link CodecFactory} the application chose, then encrypted
 * by codec's {@code EnvelopeCodec} inside a {@code VersionedCodec} -- see {@link
 * JdbcStorageConfig}. This module contains no cryptography of its own: the envelope constitutes a
 * fresh data key per payload and wraps it with a key named by id, which is what makes key rotation
 * a matter of adding a key rather than rewriting a table.
 *
 * <p><b>The label is encrypted too.</b> A label can be as sensitive as the value: a tenant's name
 * or a project codeword sitting in the clear beside the ciphertext describes what the ciphertext is
 * to anyone who can read the table.
 *
 * <p>Derived values arrive with their parentage, and reachability is maintained as they are stored,
 * so erasing a value and everything made from it is one indexed query.
 */
public final class JdbcStorage implements Storage {

  /**
   * Durable storage for a charter with these axes.
   *
   * <p>The axes are the charter's, passed rather than restated: a label is stored one axis at a
   * time and keyed by name, so reading one back needs to know which axes the charter declares.
   */
  static JdbcStorage of(
      DataSource dataSource,
      CodecFactory codecs,
      Codec<byte[]> storageCodec,
      Axes axes,
      String rootId,
      Function<String, byte[]> roots,
      MacAlgorithm mac) {
    return new JdbcStorage(dataSource, codecs, storageCodec, axes, rootId, roots, mac);
  }

  private static final String INSERT_AUDIT =
      """
      INSERT INTO occlude_audit
        (recorded_at, operation, value_id, target, outcome, reason, detail, label, previous, digest,
         root_id, context, commitment, mac)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String INSERT_VALUE =
      """
      INSERT INTO occlude_value
        (value_id, value_type, payload, label, derivation, digest, root_id, payload_commitment,
         label_commitment, mac)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String SELECT_METADATA =
      "SELECT value_id, value_type, label, label_commitment, derivation, root_id, mac"
          + " FROM occlude_value WHERE value_id = ?";
  private static final String SELECT_PAYLOAD =
      "SELECT value_id, payload, payload_commitment, root_id, mac"
          + " FROM occlude_value WHERE value_id = ?";
  private static final String SELECT_METADATA_MANY =
      "SELECT value_id, value_type, label, label_commitment, derivation, root_id, mac"
          + " FROM occlude_value WHERE value_id = ANY (?)";
  private static final String SELECT_PAYLOAD_MANY =
      "SELECT value_id, payload, payload_commitment, root_id, mac"
          + " FROM occlude_value WHERE value_id = ANY (?)";
  private static final String SELECT_PARENTS =
      "SELECT parent_id FROM occlude_lineage WHERE child_id = ? ORDER BY position";
  private static final String INSERT_PARENT =
      """
      INSERT INTO occlude_lineage (child_id, parent_id, position) VALUES (?, ?, ?)
      ON CONFLICT (child_id, parent_id) DO NOTHING
      """;

  // Column names, read back through ResultSet#getBytes/getString often enough that the literal
  // itself is worth naming once.
  private static final String COL_ENTRY_ID = "entry_id";
  private static final String COL_VALUE_ID = "value_id";
  private static final String COL_VALUE_TYPE = "value_type";
  private static final String COL_LABEL = "label";
  private static final String COL_DERIVATION = "derivation";
  private static final String COL_DIGEST = "digest";
  private static final String COL_PAYLOAD = "payload";
  private static final String COL_ROOT_ID = "root_id";
  private static final String COL_MAC = "mac";
  private static final String COL_COMMITMENT = "commitment";
  private static final String COL_PAYLOAD_COMMITMENT = "payload_commitment";
  private static final String COL_LABEL_COMMITMENT = "label_commitment";
  private static final String COL_DETAIL = "detail";
  private static final String COL_CONTEXT = "context";

  /** What a commitment is to, so a label's cannot be passed off as a payload's. */
  private static final String PAYLOAD = "payload";

  private static final String LABEL = "label";

  /**
   * Everything reachable from a value, walked at the moment of erasing rather than maintained.
   *
   * <p>Computed from occlude_lineage, which is covered by the value digests: a child hashes from
   * its parents, so altering who a value was made from breaks that value and everything below it.
   * It used to read a closure table that nothing signed, which decided what erasure destroyed -- so
   * deleting one closure row left a value derived from erased customer data alive, and no verifier
   * noticed. Deriving the answer from the signed structure removes the trusted one rather than
   * protecting it.
   */
  private static final String DELETE_REACHABLE =
      """
      WITH RECURSIVE reachable (value_id) AS (
        SELECT CAST(? AS TEXT)
        UNION
        SELECT lineage.child_id
          FROM occlude_lineage lineage
          JOIN reachable ON lineage.parent_id = reachable.value_id
      )
      DELETE FROM occlude_value WHERE value_id IN (SELECT value_id FROM reachable)
      RETURNING value_id
      """;

  private final DataSource dataSource;
  private final CodecFactory codecs;
  private final Codec<byte[]> storageCodec;

  /**
   * Serialisation alone, for the two things stored as a map of strings: a label, and whatever the
   * application calls identity. Kept apart from the encryption because what is committed to is the
   * plaintext these produce, and what is written is that plaintext encrypted.
   */
  private final Codec<Map<String, String>> stringMaps;

  private final Axes axes;
  private final String rootId;
  private final Function<String, byte[]> roots;
  private final MacAlgorithm mac;
  private final Map<String, Codec<?>> byType = new ConcurrentHashMap<>();

  private JdbcStorage(
      DataSource dataSource,
      CodecFactory codecs,
      Codec<byte[]> storageCodec,
      Axes axes,
      String rootId,
      Function<String, byte[]> roots,
      MacAlgorithm mac) {
    this.dataSource = dataSource;
    this.codecs = codecs;
    this.storageCodec = storageCodec;
    this.axes = axes;
    this.rootId = rootId;
    this.roots = roots;
    this.mac = mac;
    // One axis at a time, keyed by name. A record would have gone to disk positionally, and then
    // declaring a fourth axis would make every row already written undecodable.
    this.stringMaps =
        codecs.create(TypeRef.mapOf(TypeRef.of(String.class), TypeRef.of(String.class)));
  }

  /** Creates the tables if they are not there. */
  public void migrate() {
    // Comments first: the schema explains itself at length, and a semicolon in a sentence used to
    // cut a CREATE TABLE in half.
    String sql =
        read("schema-postgresql.sql")
            .lines()
            .map(line -> line.strip().startsWith("--") ? "" : line)
            .collect(Collectors.joining("\n"));
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String each :
          Arrays.stream(sql.split(";")).filter(Predicate.not(String::isBlank)).toList()) {
        statement.execute(each);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not create the store schema", e);
    }
  }

  @Override
  public void put(String id, StoredValue value, AuditRecord auditRecord) {
    inTransaction(
        "could not store " + id,
        connection -> {
          // Before anything is read, so an erasure cannot begin between reading the parents and
          // writing the child. A value with no parents cannot be a descendant of anything, so it
          // does not contend.
          if (!value.lineage().parents().isEmpty()) {
            lockLineageShared(connection);
          }
          insertValue(connection, id, value);
          insertLineage(connection, id, value.lineage());
          insertAudit(connection, auditRecord);
        });
  }

  /**
   * One write, in a transaction this library chooses the isolation of.
   *
   * <p>READ COMMITTED is pinned rather than inherited, and the chain depends on it. A REPEATABLE
   * READ transaction fixes its snapshot at its first statement, which here is the one taking the
   * advisory lock -- so an appender queues correctly, then reads the head as it was before it
   * queued, and several lines commit naming the same predecessor. Nobody has attacked anything and
   * the trail reports itself broken, which is worse than useless: it teaches operators that the
   * verifier cries wolf. Pools configured REPEATABLE READ are ordinary, so this cannot be a hope.
   *
   * <p>Both the isolation level and the auto-commit flag are put back, because the connection goes
   * back to a pool that lent it on the terms it had.
   */
  private void inTransaction(String what, SqlWork work) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      int isolation = connection.getTransactionIsolation();
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      boolean committed = false;
      try {
        work.run(connection);
        connection.commit();
        committed = true;
      } finally {
        // Throwable, not RuntimeException. Restoring auto-commit COMMITS whatever is in flight,
        // so an Error on its way out -- an OutOfMemoryError inside an application-supplied codec,
        // say -- would have committed the value rows without the audit line that has to accompany
        // them. Rolling back first is what makes "restore the connection" safe to do afterwards.
        if (!committed) {
          rollbackQuietly(connection);
        }
        connection.setAutoCommit(autoCommit);
        connection.setTransactionIsolation(isolation);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(what, e);
    }
  }

  /** The same, for a write that has something to report back. */
  private <T> T inTransactionReturning(String what, SqlAnswer<T> work) {
    AtomicReference<T> answer = new AtomicReference<>();
    inTransaction(what, connection -> answer.set(work.run(connection)));
    return answer.get();
  }

  /**
   * Rolls back without replacing the exception that is already on its way out.
   *
   * <p>A rollback that throws used to mask the original failure, which is the one worth reading:
   * the reason the work failed explains the rollback, never the other way round.
   */
  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException _) {
      // Nothing to do with it that would not hide why we are here.
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection connection) throws SQLException;
  }

  @FunctionalInterface
  private interface SqlAnswer<T> {
    T run(Connection connection) throws SQLException;
  }

  @Override
  public void append(AuditRecord entry) {
    inTransaction(
        "could not record " + entry.operation(), connection -> insertAudit(connection, entry));
  }

  /**
   * Writes one line of the trail.
   *
   * <p>The label is encrypted and everything else is not, for the reason set out in the schema: a
   * trail nobody can query is a tape backup, and a label names a tenant.
   */
  private void insertAudit(Connection connection, AuditRecord entry) throws SQLException {
    // Ordered, because a line has a predecessor rather than parents. An advisory lock rather than
    // a row: the first append has nothing to lock, and inventing a row only to lock it is how the
    // same fact ends up stored twice.
    Predecessor head = lockTrailHead(connection);
    byte[] previous = head.digest();
    byte[] detail = entry.detail().map(text -> text.getBytes(UTF_8)).orElse(null);
    byte[] label = entry.label().map(text -> text.getBytes(UTF_8)).orElse(null);
    byte[] context = stringMaps.encode(entry.context());
    // The database's clock, not this process's: a trail signs facts it witnessed, and when some
    // application server believed it decided something is not one of them. Truncated once, because
    // TIMESTAMPTZ keeps microseconds and an Instant offers nanoseconds -- signing what was in hand
    // rather than what reached the column made every line fail its own check when it was read back.
    Instant recordedAt = head.recordedAt().truncatedTo(ChronoUnit.MICROS);
    // What is signed is a commitment to the plaintext, never its ciphertext, so the line survives
    // being re-encrypted and survives a destroyed key. Bound to the line's place in the chain, so
    // two lines saying the same thing do not commit to it identically.
    byte[] commitment = lineCommitment(rootId, mac, previous, recordedAt, detail, label, context);
    byte[] digest = lineDigest(rootId, mac, previous, recordedAt, LineFacts.of(entry), commitment);
    try (PreparedStatement statement = connection.prepareStatement(INSERT_AUDIT)) {
      statement.setTimestamp(1, Timestamp.from(recordedAt));
      statement.setString(2, entry.operation().name());
      statement.setString(3, entry.value());
      statement.setString(4, entry.target().orElse(null));
      statement.setString(5, entry.outcome().name());
      statement.setString(6, entry.reason().orElse(null));
      statement.setBytes(7, encrypted(detail));
      statement.setBytes(8, encrypted(label));
      statement.setBytes(9, previous);
      statement.setBytes(10, digest);
      statement.setString(11, rootId);
      statement.setBytes(12, storageCodec.encode(context));
      statement.setBytes(13, commitment);
      statement.setString(14, mac.jcaName());
      statement.executeUpdate();
    }
  }

  /** Through the pipeline, keeping an absent field absent. */
  private byte[] encrypted(byte[] plaintext) {
    return plaintext == null ? null : storageCodec.encode(plaintext);
  }

  /** Back out of the pipeline, keeping an absent field absent. */
  private byte[] decrypted(byte[] ciphertext) {
    return ciphertext == null ? null : storageCodec.decode(ciphertext);
  }

  private void insertValue(Connection connection, String id, StoredValue value)
      throws SQLException {
    byte[] payload = serialised(value.type().type(), value.value());
    byte[] label = stringMaps.encode(value.label().encode());
    ValueCommitments commitments =
        new ValueCommitments(
            valueCommitment(rootId, mac, id, PAYLOAD, payload),
            valueCommitment(rootId, mac, id, LABEL, label));
    // From the parents, which are immutable and already written, so nothing here is locked and two
    // derivations never wait on each other. A fresh value has none and starts its own graph.
    byte[] digest =
        digestOf(
            rootId,
            mac,
            id,
            value.type().name(),
            commitments,
            value.lineage().derivation().orElse(null),
            parentDigests(connection, value));
    try (PreparedStatement statement = connection.prepareStatement(INSERT_VALUE)) {
      statement.setString(1, id);
      statement.setString(2, value.type().name());
      statement.setBytes(3, storageCodec.encode(payload));
      statement.setBytes(4, storageCodec.encode(label));
      statement.setString(5, value.lineage().derivation().orElse(null));
      statement.setBytes(6, digest);
      statement.setString(7, rootId);
      statement.setBytes(8, commitments.payload());
      statement.setBytes(9, commitments.label());
      statement.setString(10, mac.jcaName());
      statement.executeUpdate();
    }
  }

  /**
   * What this value was made from, locked against disappearing underneath it.
   *
   * <p>{@code FOR SHARE} rather than a plain read. Without it a derivation could read its parents,
   * an erasure of those parents could commit, and the derivation could then commit a child of
   * values that no longer exist -- a descendant of data somebody asked to have destroyed, left
   * alive because the two transactions never saw each other. A share lock lets any number of
   * derivations read the same parents at once and makes the erasure wait, which is the right way
   * round: erasing is rare and must be complete, deriving is common and must not block itself.
   */
  private List<byte[]> parentDigests(Connection connection, StoredValue value) throws SQLException {
    List<String> parents = value.lineage().parents();
    List<byte[]> digests = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT digest FROM occlude_value WHERE value_id = ? FOR SHARE")) {
      for (String parent : parents) {
        statement.setString(1, parent);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            throw new IllegalStateException(
                "cannot derive from " + parent + ", which this store is not holding");
          }
          digests.add(rows.getBytes(COL_DIGEST));
        }
      }
    }
    return digests;
  }

  /**
   * The digest, or empty when this row cannot be checked at all.
   *
   * <p>A row naming a root nothing supplies is a finding, not a crash. Letting that throw handed an
   * attacker a way to turn "broken at this value" into "the verifier does not run": rewrite one
   * root_id and the whole report becomes an exception. It is also what an ordinary rotation looks
   * like once an old root is retired.
   */
  private byte[] digestOfOrNull(
      String under,
      String algorithm,
      String id,
      String type,
      ValueCommitments commitments,
      String derivation,
      List<byte[]> parents) {
    MacAlgorithm named = MacAlgorithm.named(algorithm);
    if (named == null) {
      // A row naming a MAC this store will not verify with is broken, not a reason to use it.
      return new byte[0];
    }
    try {
      return digestOf(under, named, id, type, commitments, derivation, parents);
    } catch (IllegalStateException _) {
      return new byte[0];
    }
  }

  /**
   * What a value hashes to: what it commits to, and whatever it was derived from.
   *
   * <p>Position matters, so a derivation over the same parents in a different order is a different
   * value. Every field is length-prefixed, so no two different graphs encode to the same bytes by
   * running one value into the next.
   *
   * <p>Keyed by the root. Rooted in a constant this is tamper-evident: an edit is visible, but
   * somebody with write access can recompute the graph below it. Rooted in a secret the database
   * does not hold, no node can be forged at all.
   */
  private byte[] digestOf(
      String under,
      MacAlgorithm algorithm,
      String id,
      String type,
      ValueCommitments commitments,
      String derivation,
      List<byte[]> parents) {
    Mac mac = keyed(Domain.VALUE, under, algorithm);
    // Counted before they are fed. The parents are the only run whose length varies, so without a
    // count a value with two parents and a value with one could be fed identical bytes.
    feedCount(mac, parents.size());
    for (byte[] parent : parents) {
      feed(mac, parent);
    }
    feed(mac, id.getBytes(UTF_8));
    feed(mac, type.getBytes(UTF_8));
    feed(mac, commitments.payload());
    feed(mac, commitments.label());
    // What made it. Left out, it was free to change: the parents stayed right, the digest stayed
    // right, and lineage() named a derivation that had never run.
    feed(mac, derivation == null ? null : derivation.getBytes(UTF_8));
    return mac.doFinal();
  }

  /**
   * What kind of thing is being signed.
   *
   * <p>One key signs both the value graph and the trail. Without a tag they share a MAC, so a
   * construction in one is a construction in the other. Fed before anything an attacker influences,
   * so no value's bytes can ever be read back as a line's.
   */
  private enum Domain {
    VALUE,
    LINE,
    COMMITMENT
  }

  /**
   * Keyed by a root, which is why none of this can be recomputed by whoever can write.
   *
   * <p>Named, so rotating a root does not invalidate what was written under the last one. The id is
   * signed too, so two stores sharing a secret still produce different digests.
   */
  private Mac keyed(Domain domain, String id, MacAlgorithm algorithm) {
    byte[] secret = roots.apply(id);
    // Empty is as good as absent -- and left alone it would not even reach the MAC: SecretKeySpec
    // throws IllegalArgumentException on it, which the verifier does not expect, so a lookup that
    // answered with nothing would crash the report instead of marking the row broken.
    if (secret == null || secret.length == 0) {
      throw new IllegalStateException(
          "nothing supplies the root '" + id + "', which some of this was written under");
    }
    try {
      Mac mac = Mac.getInstance(algorithm.jcaName());
      mac.init(new SecretKeySpec(secret, algorithm.jcaName()));
      mac.update((byte) domain.ordinal());
      feed(mac, id.getBytes(UTF_8));
      // The algorithm is signed too, so a row cannot claim one it was not signed with.
      feed(mac, algorithm.jcaName().getBytes(UTF_8));
      return mac;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("this JVM cannot compute " + algorithm.jcaName(), e);
    }
  }

  /** What a value's payload and label commit to, fed to its digest in that order. */
  private record ValueCommitments(byte[] payload, byte[] label) {

    @Override
    public boolean equals(Object other) {
      return other instanceof ValueCommitments(byte[] thatPayload, byte[] thatLabel)
          && Arrays.equals(payload, thatPayload)
          && Arrays.equals(label, thatLabel);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(payload) + Arrays.hashCode(label);
    }

    @Override
    public String toString() {
      return "ValueCommitments";
    }
  }

  /**
   * A keyed commitment to one field of one value.
   *
   * <p>Keyed, because an unkeyed hash of a small value -- a last-4, a role -- is found by trying
   * them all. Bound to the value's id and to which field it is, so equal plaintexts in two rows
   * commit differently and a label's commitment cannot stand in for a payload's.
   */
  private byte[] valueCommitment(
      String under, MacAlgorithm algorithm, String id, String field, byte[] plaintext) {
    Mac commitment = keyed(Domain.COMMITMENT, under, algorithm);
    feed(commitment, id.getBytes(UTF_8));
    feed(commitment, field.getBytes(UTF_8));
    feed(commitment, plaintext);
    return commitment.doFinal();
  }

  /** A keyed commitment to a line's protected fields, bound to its place in the chain. */
  private byte[] lineCommitment(
      String under,
      MacAlgorithm algorithm,
      byte[] previous,
      Instant recordedAt,
      byte[] detail,
      byte[] label,
      byte[] context) {
    Mac commitment = keyed(Domain.COMMITMENT, under, algorithm);
    feed(commitment, previous);
    feed(commitment, recordedAt.toString().getBytes(UTF_8));
    feed(commitment, detail);
    feed(commitment, label);
    feed(commitment, context);
    return commitment.doFinal();
  }

  /** How many of whatever follows, so two different shapes cannot feed the same bytes. */
  private static void feedCount(Mac mac, int count) {
    mac.update(
        new byte[] {
          (byte) (count >>> 24), (byte) (count >>> 16), (byte) (count >>> 8), (byte) count
        });
  }

  private static void feed(Mac mac, byte[] field) {
    int length = field == null ? -1 : field.length;
    mac.update(
        new byte[] {
          (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length
        });
    if (field != null) {
      mac.update(field);
    }
  }

  /** One name every appender waits on, so the trail has one order. */
  private static final long TRAIL_LOCK = 0x10C_A0D17L;

  /**
   * What deriving and erasing contend on, so that one cannot miss the other.
   *
   * <p>A share lock on the parent rows is not enough, and the way it fails is worth writing down.
   * Under READ COMMITTED a blocked DELETE resumes with the snapshot its statement began with. So an
   * erasure could take its snapshot -- in which the parent has no children -- block on a
   * derivation's share lock, and then resume and delete only what it saw. The child committed in
   * between is invisible to it: a value derived from erased data survives, no ERASE line names it,
   * and its parent is gone, so the value verifier reports it broken forever.
   *
   * <p>Shared for deriving and exclusive for erasing. Any number of derivations proceed at once; an
   * erasure waits for the ones in flight and shuts out the ones that would start. That is the right
   * way round: deriving is common and must not block itself, erasing is rare and must be complete.
   * Taken before the trail lock in both paths, so the two are always acquired in the same order and
   * cannot deadlock against each other.
   */
  private static final long LINEAGE_LOCK = 0x10C_11AE9L;

  /** Waits for every derivation in flight, and holds off the ones that have not started. */
  private static void lockLineageExclusively(Connection connection) throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
      lock.setLong(1, LINEAGE_LOCK);
      lock.execute();
    }
  }

  /** Shared, so derivations never wait on one another -- only on an erasure. */
  private static void lockLineageShared(Connection connection) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock_shared(?)")) {
      lock.setLong(1, LINEAGE_LOCK);
      lock.execute();
    }
  }

  /**
   * The line this one follows, and the moment this one is being written.
   *
   * <p>Both come back from the database, and the time is read <i>while the lock is held</i>. That
   * is the whole point of taking it here rather than in the caller: the lock is what decides this
   * line's position in the chain, so a clock sampled after it cannot disagree with that position.
   * {@code now()} would not do -- it is the transaction's start time, fixed before the lock was
   * ever asked for, so two appenders could still land in an order their timestamps deny.
   *
   * <p>It costs nothing. The lock had to be taken anyway, and a statement that takes it can return
   * a column while it is at it.
   */
  private record Predecessor(byte[] digest, Instant recordedAt) {
    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Predecessor(byte[] thatDigest, Instant thatRecordedAt))) {
        return false;
      }
      return Arrays.equals(digest, thatDigest) && recordedAt.equals(thatRecordedAt);
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(digest), recordedAt);
    }

    @Override
    public String toString() {
      return "Predecessor[digest=" + Arrays.toString(digest) + ", recordedAt=" + recordedAt + "]";
    }
  }

  private static Predecessor lockTrailHead(Connection connection) throws SQLException {
    Instant recordedAt;
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?), clock_timestamp() AS now")) {
      lock.setLong(1, TRAIL_LOCK);
      try (ResultSet rows = lock.executeQuery()) {
        rows.next();
        recordedAt = rows.getTimestamp("now").toInstant();
      }
    }
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT digest FROM occlude_audit ORDER BY entry_id DESC LIMIT 1");
        ResultSet rows = statement.executeQuery()) {
      return new Predecessor(rows.next() ? rows.getBytes(COL_DIGEST) : null, recordedAt);
    }
  }

  /**
   * The fields of one line that are not already parameters in their own right: bundled so the
   * method that signs a line stays under the parameter count this project holds every method to,
   * without changing which bytes are fed or in what order.
   */
  private record LineFacts(
      String operation, String value, String target, String outcome, String reason) {

    private static LineFacts of(AuditRecord entry) {
      return new LineFacts(
          entry.operation().name(),
          entry.value(),
          entry.target().orElse(null),
          entry.outcome().name(),
          entry.reason().orElse(null));
    }
  }

  private byte[] lineDigest(
      String under,
      MacAlgorithm algorithm,
      byte[] previous,
      Instant recordedAt,
      LineFacts facts,
      byte[] commitment) {
    Mac mac = keyed(Domain.LINE, under, algorithm);
    feed(mac, previous);
    feed(mac, recordedAt.toString().getBytes(UTF_8));
    feed(mac, facts.operation().getBytes(UTF_8));
    feed(mac, facts.value().getBytes(UTF_8));
    feed(mac, facts.target() == null ? null : facts.target().getBytes(UTF_8));
    feed(mac, facts.outcome().getBytes(UTF_8));
    feed(mac, facts.reason() == null ? null : facts.reason().getBytes(UTF_8));
    feed(mac, commitment);
    return mac.doFinal();
  }

  /** The digest of one trail line, or empty when it cannot be checked at all. */
  private Optional<byte[]> lineDigestIfSigned(
      String under,
      String algorithm,
      byte[] previous,
      Instant recordedAt,
      LineFacts facts,
      byte[] commitment) {
    MacAlgorithm named = MacAlgorithm.named(algorithm);
    if (named == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(lineDigest(under, named, previous, recordedAt, facts, commitment));
    } catch (IllegalStateException _) {
      return Optional.empty();
    }
  }

  /**
   * The digest of the last line written, for publishing somewhere this database cannot reach.
   *
   * <p>The one thing verification cannot do on its own is notice lines cut from the end: what
   * remains is a valid trail that simply stopped earlier, and no structure over data an attacker
   * controls can say otherwise. An anchor is the answer -- write this down elsewhere, periodically,
   * and compare with {@link #stillHolds(TrailHead)}. It is one line, so a log entry or a printout
   * will do.
   *
   * @return the head, or empty when nothing has been recorded yet
   */
  public Optional<TrailHead> head() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT entry_id, digest FROM occlude_audit ORDER BY entry_id DESC LIMIT 1");
        ResultSet rows = statement.executeQuery()) {
      return rows.next()
          ? Optional.of(new TrailHead(rows.getLong(COL_ENTRY_ID), rows.getBytes(COL_DIGEST)))
          : Optional.empty();
    } catch (SQLException e) {
      throw new IllegalStateException("could not read the head of the trail", e);
    }
  }

  /**
   * Whether the trail still contains a head published earlier, exactly as it was.
   *
   * <p>False when that line is gone -- the trail was cut back past it -- or says something else.
   * Together with {@link #firstBrokenEntry()}, which says everything up to the end agrees with
   * itself, this is what notices truncation.
   */
  public boolean stillHolds(TrailHead anchor) {
    Objects.requireNonNull(anchor, "an anchor must not be null");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT digest FROM occlude_audit WHERE entry_id = ?")) {
      statement.setLong(1, anchor.entryId());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() && Arrays.equals(rows.getBytes(COL_DIGEST), anchor.digest());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not look for the anchor in the trail", e);
    }
  }

  /** How many rows one re-encryption transaction takes, so none holds its locks for long. */
  private static final int REENCRYPT_PAGE = 500;

  /**
   * Re-encrypts everything this store holds under its current keys and pipeline.
   *
   * <p>What makes a key retirable. Every value's payload and label, and every line's detail, label
   * and context, is decrypted, checked against the commitment signed for it, and encrypted again
   * under the key and pipeline version this store writes with now. Nothing signed changes -- the
   * digests cover commitments, not ciphertext -- so the trail and the value graph verify exactly as
   * before, and once this returns the old key protects nothing and can be destroyed.
   *
   * <p>A field that does not match its commitment stops the run rather than being re-encrypted:
   * encrypting it afresh would launder a ciphertext somebody swapped in into one that looks like
   * this store wrote it. Pages are committed as they go, so an interrupted run is resumed by
   * running it again; a field already under the current key is simply re-encrypted once more.
   *
   * <p>Every key a stored field names must still be available while this runs.
   *
   * @return how many rows were rewritten
   * @throws IllegalStateException if a field is not what was signed for it
   */
  public int reencrypt() {
    int rewritten = 0;
    String afterValue = "";
    while (afterValue != null) {
      String from = afterValue;
      Page<String> page =
          inTransactionReturning(
              "could not re-encrypt values", connection -> reencryptValues(connection, from));
      rewritten += page.rewritten();
      afterValue = page.last();
    }
    Long afterLine = 0L;
    while (afterLine != null) {
      long from = afterLine;
      Page<Long> page =
          inTransactionReturning(
              "could not re-encrypt the trail", connection -> reencryptLines(connection, from));
      rewritten += page.rewritten();
      afterLine = page.last();
    }
    return rewritten;
  }

  /** One page of a re-encryption: how many rows it rewrote, and where the next one starts. */
  private record Page<K>(int rewritten, K last) {}

  private Page<String> reencryptValues(Connection connection, String after) throws SQLException {
    // Shared, as deriving takes it: an erasure waits for this page rather than deadlocking on the
    // rows it has locked, and derivations proceed alongside it.
    lockLineageShared(connection);
    String last = null;
    int rewritten = 0;
    try (PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT value_id, payload, label, payload_commitment, label_commitment, root_id, mac
                  FROM occlude_value WHERE value_id > ? ORDER BY value_id LIMIT ? FOR UPDATE
                """);
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE occlude_value SET payload = ?, label = ? WHERE value_id = ?")) {
      select.setString(1, after);
      select.setInt(2, REENCRYPT_PAGE);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          last = rows.getString(COL_VALUE_ID);
          update.setBytes(1, storageCodec.encode(payloadOf(rows)));
          update.setBytes(2, storageCodec.encode(labelPlaintextOf(rows)));
          update.setString(3, last);
          update.addBatch();
          rewritten++;
        }
      }
      update.executeBatch();
    }
    return new Page<>(rewritten, rewritten < REENCRYPT_PAGE ? null : last);
  }

  private Page<Long> reencryptLines(Connection connection, long after) throws SQLException {
    Long last = null;
    int rewritten = 0;
    try (PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT entry_id, recorded_at, previous, detail, label, context, commitment, root_id,
                       mac
                  FROM occlude_audit WHERE entry_id > ? ORDER BY entry_id LIMIT ? FOR UPDATE
                """);
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE occlude_audit SET detail = ?, label = ?, context = ? WHERE entry_id = ?")) {
      select.setLong(1, after);
      select.setInt(2, REENCRYPT_PAGE);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          last = rows.getLong(COL_ENTRY_ID);
          byte[] detail = decrypted(rows.getBytes(COL_DETAIL));
          byte[] label = decrypted(rows.getBytes(COL_LABEL));
          byte[] context = storageCodec.decode(rows.getBytes(COL_CONTEXT));
          MacAlgorithm algorithm = MacAlgorithm.named(rows.getString(COL_MAC));
          if (algorithm == null
              || !MessageDigest.isEqual(
                  lineCommitment(
                      rows.getString(COL_ROOT_ID),
                      algorithm,
                      rows.getBytes("previous"),
                      rows.getTimestamp("recorded_at").toInstant(),
                      detail,
                      label,
                      context),
                  rows.getBytes(COL_COMMITMENT))) {
            throw new IllegalStateException(
                "line " + last + " of the trail is not what was signed for it");
          }
          update.setBytes(1, encrypted(detail));
          update.setBytes(2, encrypted(label));
          update.setBytes(3, storageCodec.encode(context));
          update.setLong(4, last);
          update.addBatch();
          rewritten++;
        }
      }
      update.executeBatch();
    }
    return new Page<>(rewritten, rewritten < REENCRYPT_PAGE ? null : last);
  }

  /**
   * Where the trail stops agreeing with itself.
   *
   * <p>A line that was edited fails its own digest. A line that was removed leaves the next one
   * naming a predecessor that is not there. Neither can be papered over without the key, which is
   * the difference between noticing a careless edit and noticing a deliberate one.
   *
   * @return the id of the first line that does not agree, or empty when the trail is intact
   */
  public Optional<Long> firstBrokenEntry() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT entry_id, recorded_at, operation, value_id, target, outcome, reason,
                       previous, digest, root_id, commitment, mac
                FROM occlude_audit ORDER BY entry_id
                """);
        ResultSet rows = statement.executeQuery()) {
      return firstBrokenIn(rows);
    } catch (SQLException e) {
      throw new IllegalStateException("could not read the trail back", e);
    }
  }

  /** The chain walk itself, over rows already selected in order. */
  private Optional<Long> firstBrokenIn(ResultSet rows) throws SQLException {
    byte[] expected = null;
    while (rows.next()) {
      byte[] previous = rows.getBytes("previous");
      if (!Arrays.equals(previous, expected)) {
        return Optional.of(rows.getLong(COL_ENTRY_ID));
      }
      // A root nothing supplies is a broken line, not a crashed verifier. Left to throw, an
      // attacker who could edit a line could rewrite its root_id instead and turn "broken at
      // entry 7" into an exception that reports nothing at all.
      LineFacts facts =
          new LineFacts(
              rows.getString("operation"),
              rows.getString(COL_VALUE_ID),
              rows.getString("target"),
              rows.getString("outcome"),
              rows.getString("reason"));
      // Commitments rather than ciphertext, so no key is needed here: the trail verifies even
      // after the key that encrypted a line has been destroyed.
      Optional<byte[]> digest =
          lineDigestIfSigned(
              rows.getString(COL_ROOT_ID),
              rows.getString(COL_MAC),
              previous,
              rows.getTimestamp("recorded_at").toInstant(),
              facts,
              rows.getBytes(COL_COMMITMENT));
      if (digest.isEmpty() || !Arrays.equals(digest.get(), rows.getBytes(COL_DIGEST))) {
        return Optional.of(rows.getLong(COL_ENTRY_ID));
      }
      expected = digest.get();
    }
    return Optional.empty();
  }

  /**
   * Every value the trail says should be here and is not.
   *
   * <p>The gap the value graph cannot see. A value's digest binds it to its ancestry, so editing
   * one breaks its children and deleting one breaks them too -- but a <b>leaf</b> has no children,
   * and deleting it leaves nothing behind to disagree with. {@link #brokenValues()} iterates the
   * rows that are still there, and a row that is gone is not one of them.
   *
   * <p>Only the trail can close that, because only the trail is outside the row. Every value was
   * announced by an ALLOWED CONCEAL or DERIVE line naming it, and every lawful removal wrote an
   * ERASE line naming it. What the trail says exists, minus what the trail says was erased, is
   * exactly what {@code occlude_value} should contain. Anything missing from that difference was
   * removed by something that did not go through this library.
   *
   * <p>This is why erasure records identifiers rather than a count, and why the value rows carry no
   * timestamp of their own: a row cannot be the evidence for its own existence.
   *
   * <p>Verifying the trail first is the point. These are claims the trail makes, so they are worth
   * exactly what {@link #firstBrokenEntry()} says they are -- an attacker who could delete the row
   * could delete its CONCEAL line too, and the chain is what makes that visible.
   *
   * @return the identifiers of values the trail announced, never erased, and which are not here
   */
  public List<String> missingValues() {
    List<String> missing = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT announced.value_id FROM (
                  SELECT DISTINCT value_id FROM occlude_audit
                   WHERE outcome = 'ALLOWED' AND operation IN ('CONCEAL', 'DERIVE')
                ) AS announced
                 WHERE announced.value_id NOT IN (
                   SELECT value_id FROM occlude_audit
                    WHERE outcome = 'ALLOWED' AND operation = 'ERASE')
                   AND announced.value_id NOT IN (SELECT value_id FROM occlude_value)
                 ORDER BY announced.value_id
                """);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        missing.add(rows.getString(COL_VALUE_ID));
      }
      return missing;
    } catch (SQLException e) {
      throw new IllegalStateException("could not check the values against the trail", e);
    }
  }

  /**
   * Every value whose digest no longer agrees with its own bytes and its ancestry.
   *
   * <p>An edited value appears here; so does every value derived from it, because their digests
   * were computed from what it used to be. A deleted value appears as its children failing to find
   * what they were made from.
   */
  public List<String> brokenValues() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT value_id, value_type, payload_commitment, label_commitment, digest,"
                    + " root_id, mac, derivation FROM occlude_value ORDER BY value_id");
        ResultSet rows = statement.executeQuery()) {
      Map<String, ValueRow> byId = loadValueRows(rows);
      List<String> broken = verifyToFixpoint(connection, byId);
      broken.sort(Comparator.naturalOrder());
      return broken;
    } catch (SQLException e) {
      throw new IllegalStateException("could not read the values back", e);
    }
  }

  /**
   * One row of {@code occlude_value}, read once and passed around rather than re-queried.
   *
   * <p>Arrays compare by identity, so a record holding them gets an equals that answers "no" for
   * two rows carrying the same bytes. Nothing here compares one today; written out anyway, because
   * the first thing to put one in a set would get a silently wrong answer.
   */
  private record ValueRow(
      byte[] digest,
      ValueCommitments commitments,
      String derivation,
      String type,
      String rootId,
      String mac) {

    @Override
    public boolean equals(Object other) {
      return other
              instanceof
              ValueRow(
                  byte[] otherDigest,
                  ValueCommitments otherCommitments,
                  String otherDerivation,
                  String otherType,
                  String otherRootId,
                  String otherMac)
          && Arrays.equals(digest, otherDigest)
          && Objects.equals(commitments, otherCommitments)
          && Objects.equals(derivation, otherDerivation)
          && Objects.equals(type, otherType)
          && Objects.equals(rootId, otherRootId)
          && Objects.equals(mac, otherMac);
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(digest), commitments, derivation, type, rootId, mac);
    }

    @Override
    public String toString() {
      return "ValueRow[type=%s, derivation=%s, rootId=%s, mac=%s]"
          .formatted(type, derivation, rootId, mac);
    }
  }

  private Map<String, ValueRow> loadValueRows(ResultSet rows) throws SQLException {
    Map<String, ValueRow> byId = new LinkedHashMap<>();
    while (rows.next()) {
      String id = rows.getString(COL_VALUE_ID);
      byId.put(
          id,
          new ValueRow(
              rows.getBytes(COL_DIGEST),
              new ValueCommitments(
                  rows.getBytes(COL_PAYLOAD_COMMITMENT), rows.getBytes(COL_LABEL_COMMITMENT)),
              rows.getString(COL_DERIVATION),
              rows.getString(COL_VALUE_TYPE),
              rows.getString(COL_ROOT_ID),
              rows.getString(COL_MAC)));
    }
    return byId;
  }

  /**
   * Verifies whatever can be verified, to a fixpoint, rather than in one pass over sorted
   * identifiers. A value can only be checked once its parents have been, and nothing orders the
   * rows that way: ids are v7, so they happen to sort by creation time, but {@code Storage.freshId}
   * is a documented seam an application may replace, and two writers with skewed clocks interleave
   * anyway. Sorting by id made a perfectly good child report itself broken because its parent had
   * not been reached yet.
   *
   * <p>Each pass verifies whatever has become checkable. When a pass verifies nothing new, what is
   * left is genuinely unverifiable: broken, or descended from something broken or missing.
   */
  private List<String> verifyToFixpoint(Connection connection, Map<String, ValueRow> byId)
      throws SQLException {
    Map<String, byte[]> seen = new LinkedHashMap<>();
    List<String> broken = new ArrayList<>();
    Set<String> unresolved = new LinkedHashSet<>(byId.keySet());
    boolean progressing = true;
    while (progressing) {
      progressing = false;
      Iterator<String> remaining = unresolved.iterator();
      while (remaining.hasNext()) {
        String id = remaining.next();
        Optional<List<byte[]>> checkable = checkableParents(connection, id, seen);
        if (checkable.isEmpty()) {
          continue;
        }
        List<byte[]> parents = checkable.get();
        ValueRow row = byId.get(id);
        byte[] computed =
            digestOfOrNull(
                row.rootId(),
                row.mac(),
                id,
                row.type(),
                row.commitments(),
                row.derivation(),
                parents);
        if (Arrays.equals(computed, row.digest())) {
          seen.put(id, computed);
        } else {
          broken.add(id);
        }
        remaining.remove();
        progressing = true;
      }
    }
    // Whatever never became checkable hangs off something that is broken or gone.
    broken.addAll(unresolved);
    return broken;
  }

  /**
   * The parents' digests, or empty when this value cannot be checked yet.
   *
   * <p>An Optional rather than a null list, because "not checkable yet" and "no parents" are
   * different answers and a value with no parents is the ordinary case: a freshly occluded value
   * has none and verifies on its own.
   */
  private Optional<List<byte[]>> checkableParents(
      Connection connection, String id, Map<String, byte[]> seen) throws SQLException {
    List<byte[]> parents = new ArrayList<>();
    for (String parent : parentsOf(connection, id)) {
      byte[] digest = seen.get(parent);
      if (digest == null) {
        return Optional.empty();
      }
      parents.add(digest);
    }
    return Optional.of(parents);
  }

  private void insertLineage(Connection connection, String id, Lineage lineage)
      throws SQLException {
    List<String> parents = lineage.parents();
    try (PreparedStatement parent = connection.prepareStatement(INSERT_PARENT)) {
      parent.setString(1, id);
      for (int i = 0; i < parents.size(); i++) {
        parent.setString(2, parents.get(i));
        parent.setInt(3, i);
        parent.addBatch();
      }
      // One round trip rather than one per parent. A fold over ten values wrote ten times here.
      parent.executeBatch();
    }
  }

  @Override
  public Optional<StoredMetadata> metadata(String id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_METADATA)) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(metadataOf(connection, rows)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + id, e);
    }
  }

  /** One row's type, checked label and lineage. */
  private StoredMetadata metadataOf(Connection connection, ResultSet rows) throws SQLException {
    String derivation = rows.getString(COL_DERIVATION);
    Lineage lineage =
        derivation == null
            ? Lineage.occluded()
            : Lineage.derivedFrom(parentsOf(connection, rows.getString(COL_VALUE_ID)), derivation);
    return new StoredMetadata(rows.getString(COL_VALUE_TYPE), labelOf(rows), lineage);
  }

  @Override
  public Map<String, StoredMetadata> metadata(List<String> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_METADATA_MANY)) {
      statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
      Map<String, StoredMetadata> found = new LinkedHashMap<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.put(rows.getString(COL_VALUE_ID), metadataOf(connection, rows));
        }
      }
      return found;
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + ids, e);
    }
  }

  @Override
  public Map<String, Object> values(Map<String, TypeRef<?>> wanted) {
    if (wanted.isEmpty()) {
      return Map.of();
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_PAYLOAD_MANY)) {
      statement.setArray(1, connection.createArrayOf("text", wanted.keySet().toArray()));
      Map<String, Object> found = new LinkedHashMap<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String id = rows.getString(COL_VALUE_ID);
          found.put(id, serialiserFor(wanted.get(id)).decode(payloadOf(rows)));
        }
      }
      return found;
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + wanted.keySet(), e);
    }
  }

  @Override
  public <T> Optional<T> value(String id, TypeRef<T> type) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_PAYLOAD)) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? Optional.of(serialiserFor(type).decode(payloadOf(rows)))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + id, e);
    }
  }

  private List<String> parentsOf(Connection connection, String id) throws SQLException {
    List<String> parents = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(SELECT_PARENTS)) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          parents.add(rows.getString("parent_id"));
        }
      }
    }
    return parents;
  }

  @Override
  public boolean contains(String id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT 1 FROM occlude_value WHERE value_id = ?")) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not look for " + id, e);
    }
  }

  @Override
  public List<String> erase(String root, Function<String, AuditRecord> lineFor) {
    return inTransactionReturning(
        "could not erase " + root,
        connection -> {
          lockLineageExclusively(connection);
          List<String> removed = new ArrayList<>();
          // RETURNING, so the identities come back from the same statement that destroys them.
          // Selecting them first would be a second snapshot and a window to disagree with.
          try (PreparedStatement statement = connection.prepareStatement(DELETE_REACHABLE)) {
            statement.setString(1, root);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                removed.add(rows.getString(COL_VALUE_ID));
              }
            }
          }
          // In this transaction, with the deletes. A value destroyed without a line saying so is
          // indistinguishable from one somebody deleted behind the library's back.
          for (String id : removed) {
            insertAudit(connection, lineFor.apply(id));
          }
          return removed;
        });
  }

  @SuppressWarnings("unchecked")
  private byte[] serialised(TypeRef<?> type, Object value) {
    return ((Codec<Object>) serialiserFor(type)).encode(value);
  }

  /** How values of this type become bytes, before anything protects them. */
  @SuppressWarnings("unchecked")
  private <T> Codec<T> serialiserFor(TypeRef<T> type) {
    return (Codec<T>)
        byType.computeIfAbsent(type.getType().getTypeName(), name -> codecs.create(type));
  }

  /**
   * A row's label, decrypted and checked against what was signed for it.
   *
   * <p>The digests cover commitments rather than ciphertext, so a ciphertext copied in from another
   * row would pass every digest check. Checking each read against its commitment is what refuses
   * it.
   */
  private Label labelOf(ResultSet rows) throws SQLException {
    return Label.decode(stringMaps.decode(labelPlaintextOf(rows)), axes);
  }

  private byte[] labelPlaintextOf(ResultSet rows) throws SQLException {
    byte[] plaintext = storageCodec.decode(rows.getBytes(COL_LABEL));
    faithful(rows, LABEL, plaintext, rows.getBytes(COL_LABEL_COMMITMENT));
    return plaintext;
  }

  /** A row's payload, decrypted and checked against what was signed for it. */
  private byte[] payloadOf(ResultSet rows) throws SQLException {
    byte[] plaintext = storageCodec.decode(rows.getBytes(COL_PAYLOAD));
    faithful(rows, PAYLOAD, plaintext, rows.getBytes(COL_PAYLOAD_COMMITMENT));
    return plaintext;
  }

  private void faithful(ResultSet rows, String field, byte[] plaintext, byte[] committed)
      throws SQLException {
    String id = rows.getString(COL_VALUE_ID);
    MacAlgorithm algorithm = MacAlgorithm.named(rows.getString(COL_MAC));
    if (algorithm == null
        || !MessageDigest.isEqual(
            valueCommitment(rows.getString(COL_ROOT_ID), algorithm, id, field, plaintext),
            committed)) {
      throw new IllegalStateException(
          "the " + field + " stored for " + id + " is not what was signed for it");
    }
  }

  private String read(String resource) {
    try (InputStream stream = JdbcStorage.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException(resource + " is missing from the jar");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + resource, e);
    }
  }
}
