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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.Lineage;
import org.jwcarman.occlude.storage.Storage;
import org.jwcarman.occlude.storage.StoredMetadata;
import org.jwcarman.occlude.storage.StoredValue;

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
      "SELECT value_id, value_type, label, label_commitment, derivation, root_id, mac, "
          + Fields.PARENTS_OF_V
          + " FROM occlude_value v WHERE value_id = ?";

  private static final String SELECT_PAYLOAD =
      "SELECT value_id, value_type, payload, payload_commitment, root_id, mac"
          + " FROM occlude_value WHERE value_id = ?";

  private static final String SELECT_METADATA_MANY =
      "SELECT value_id, value_type, label, label_commitment, derivation, root_id, mac, "
          + Fields.PARENTS_OF_V
          + " FROM occlude_value v WHERE value_id = ANY (?)";

  private static final String SELECT_PAYLOAD_MANY =
      "SELECT value_id, value_type, payload, payload_commitment, root_id, mac"
          + " FROM occlude_value WHERE value_id = ANY (?)";

  private static final String INSERT_PARENT =
      """
      INSERT INTO occlude_lineage (child_id, parent_id, position) VALUES (?, ?, ?)
      """;

  /**
   * Every value an erasure of the given one would reach, with what its digest is checked against.
   *
   * <p>The same walk the delete makes, taken first and checked, because the walk follows the
   * lineage table and a row forged into it would otherwise decide what the erasure destroys.
   */
  private static final String SELECT_REACHABLE =
      """
      WITH RECURSIVE reachable (value_id) AS (
        SELECT CAST(? AS TEXT)
        UNION
        SELECT lineage.child_id
          FROM occlude_lineage lineage
          JOIN reachable ON lineage.parent_id = reachable.value_id
      )
      SELECT v.value_id, v.value_type, v.payload_commitment, v.label_commitment, v.derivation,
             v.digest, v.root_id, v.mac,
             (SELECT array_agg(p.digest ORDER BY l.position)
                FROM occlude_lineage l LEFT JOIN occlude_value p ON p.value_id = l.parent_id
               WHERE l.child_id = v.value_id) AS parent_digests
        FROM occlude_value v WHERE v.value_id IN (SELECT value_id FROM reachable)
      """;

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
  private static final String DELETE_VERIFIED =
      "DELETE FROM occlude_value WHERE value_id = ANY(?) RETURNING value_id";

  private final DataSource dataSource;
  private final Signer signer;
  private final Transactions transactions;
  private final Fields fields;
  private final Verification verification;
  private final Reencryption reencryption;

  private JdbcStorage(
      DataSource dataSource,
      CodecFactory codecs,
      Codec<byte[]> storageCodec,
      Axes axes,
      String rootId,
      Function<String, byte[]> roots,
      MacAlgorithm mac) {
    this.dataSource = dataSource;
    this.signer = new Signer(rootId, roots, mac);
    this.transactions = new Transactions(dataSource);
    this.fields = new Fields(codecs, storageCodec, axes, signer);
    this.verification = new Verification(dataSource, signer, fields);
    this.reencryption = new Reencryption(transactions, fields);
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
        statement.addBatch(each);
      }
      statement.executeBatch();
    } catch (SQLException e) {
      throw new IllegalStateException("could not create the store schema", e);
    }
  }

  @Override
  public void put(String id, StoredValue value, AuditRecord auditRecord) {
    transactions.inTransaction(
        "could not store " + id,
        connection -> {
          // Before anything is read, so an erasure cannot begin between reading the parents and
          // writing the child. A value with no parents cannot be a descendant of anything, so it
          // does not contend.
          if (!value.lineage().parents().isEmpty()) {
            Transactions.lockLineageShared(connection);
          }
          insertValue(connection, id, value);
          insertLineage(connection, id, value.lineage());
          insertAudit(connection, auditRecord);
        });
  }

  @Override
  public void append(AuditRecord entry) {
    transactions.inTransaction(
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
    Transactions.Predecessor head = Transactions.lockTrailHead(connection);
    byte[] previous = head.digest();
    byte[] detail = entry.detail().map(text -> text.getBytes(UTF_8)).orElse(null);
    byte[] label = entry.label().map(text -> text.getBytes(UTF_8)).orElse(null);
    byte[] context = fields.serialisedMap(entry.context());
    // The database's clock, not this process's: a trail signs facts it witnessed, and when some
    // application server believed it decided something is not one of them. Truncated once, because
    // TIMESTAMPTZ keeps microseconds and an Instant offers nanoseconds -- signing what was in hand
    // rather than what reached the column made every line fail its own check when it was read back.
    Instant recordedAt = head.recordedAt().truncatedTo(ChronoUnit.MICROS);
    // What is signed is a commitment to the plaintext, never its ciphertext, so the line survives
    // being re-encrypted and survives a destroyed key. Bound to the line's place in the chain, so
    // two lines saying the same thing do not commit to it identically.
    byte[] commitment =
        signer.lineCommitment(
            signer.rootId(), signer.mac(), previous, recordedAt, detail, label, context);
    byte[] digest =
        signer.lineDigest(
            signer.rootId(),
            signer.mac(),
            previous,
            recordedAt,
            Signer.LineFacts.of(entry),
            commitment);
    try (PreparedStatement statement = connection.prepareStatement(INSERT_AUDIT)) {
      statement.setTimestamp(1, Timestamp.from(recordedAt));
      statement.setString(2, entry.operation().name());
      statement.setString(3, entry.value());
      statement.setString(4, entry.target().orElse(null));
      statement.setString(5, entry.outcome().name());
      statement.setString(6, entry.reason().orElse(null));
      statement.setBytes(7, fields.encrypted(detail));
      statement.setBytes(8, fields.encrypted(label));
      statement.setBytes(9, previous);
      statement.setBytes(10, digest);
      statement.setString(11, signer.rootId());
      statement.setBytes(12, fields.encrypt(context));
      statement.setBytes(13, commitment);
      statement.setString(14, signer.mac().jcaName());
      statement.executeUpdate();
    }
  }

  private void insertValue(Connection connection, String id, StoredValue value)
      throws SQLException {
    byte[] payload = fields.serialised(value.type().type(), value.value());
    byte[] label = fields.serialisedMap(value.label().encode());
    Signer.ValueCommitments commitments =
        new Signer.ValueCommitments(
            signer.valueCommitment(
                signer.rootId(),
                signer.mac(),
                id,
                Signer.PAYLOAD,
                Signer.payloadFacts(value.type().name()),
                payload),
            signer.valueCommitment(
                signer.rootId(),
                signer.mac(),
                id,
                Signer.LABEL,
                Signer.labelFacts(
                    value.type().name(),
                    value.lineage().derivation().orElse(null),
                    value.lineage().parents()),
                label));
    // From the parents, which are immutable and already written, so nothing here is locked and two
    // derivations never wait on each other. A fresh value has none and starts its own graph.
    byte[] digest =
        signer.digestOf(
            signer.rootId(),
            signer.mac(),
            id,
            value.type().name(),
            commitments,
            value.lineage().derivation().orElse(null),
            parentDigests(connection, value));
    try (PreparedStatement statement = connection.prepareStatement(INSERT_VALUE)) {
      statement.setString(1, id);
      statement.setString(2, value.type().name());
      statement.setBytes(3, fields.encrypt(payload));
      statement.setBytes(4, fields.encrypt(label));
      statement.setString(5, value.lineage().derivation().orElse(null));
      statement.setBytes(6, digest);
      statement.setString(7, signer.rootId());
      statement.setBytes(8, commitments.payload());
      statement.setBytes(9, commitments.label());
      statement.setString(10, signer.mac().jcaName());
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
          digests.add(rows.getBytes(Columns.DIGEST));
        }
      }
    }
    return digests;
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
        return rows.next() ? Optional.of(fields.metadataOf(rows)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + id, e);
    }
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
          found.put(rows.getString(Columns.VALUE_ID), fields.metadataOf(rows));
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
          String id = rows.getString(Columns.VALUE_ID);
          found.put(id, fields.serialiserFor(wanted.get(id)).decode(fields.payloadOf(rows)));
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
            ? Optional.of(fields.serialiserFor(type).decode(fields.payloadOf(rows)))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not read " + id, e);
    }
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
    return transactions.inTransactionReturning(
        "could not erase " + root,
        connection -> {
          Transactions.lockLineageExclusively(connection);
          // Before anything is destroyed, and under the same lock: every value the walk reaches
          // must still agree with its own digest, which covers its parents. One that does not was
          // given a parent it never had, and the whole erasure is refused rather than widened.
          List<String> verified = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(SELECT_REACHABLE)) {
            statement.setString(1, root);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                fields.requireSigned(rows);
                verified.add(rows.getString(Columns.VALUE_ID));
              }
            }
          }
          List<String> removed = new ArrayList<>();
          // Exactly what was verified, never a second walk. The lock keeps the library's own
          // writers out, not somebody writing the tables directly, and under read committed a
          // second walk would see a lineage row they committed after the check. RETURNING, so a
          // value already gone gets no line.
          try (PreparedStatement statement = connection.prepareStatement(DELETE_VERIFIED)) {
            statement.setArray(1, connection.createArrayOf("text", verified.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) {
                removed.add(rows.getString(Columns.VALUE_ID));
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

  /**
   * This store's verification, anchoring and re-encryption, without its reads.
   *
   * <p>What to hand an operations job instead of the store itself, which can decrypt anything.
   */
  public StorageIntegrity integrity() {
    return new StorageIntegrity(this);
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
    return verification.head();
  }

  /**
   * Whether the trail still contains a head published earlier, exactly as it was.
   *
   * <p>False when that line is gone -- the trail was cut back past it -- or says something else.
   * Together with {@link #firstBrokenEntry()}, which says everything up to the end agrees with
   * itself, this is what notices truncation.
   */
  public boolean stillHolds(TrailHead anchor) {
    return verification.stillHolds(anchor);
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
    return verification.firstBrokenEntry();
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
    return verification.missingValues();
  }

  /**
   * Every value whose digest no longer agrees with its own bytes and its ancestry.
   *
   * <p>An edited value appears here; so does every value derived from it, because their digests
   * were computed from what it used to be. A deleted value appears as its children failing to find
   * what they were made from.
   */
  public List<String> brokenValues() {
    return verification.brokenValues();
  }

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
    return reencryption.reencrypt();
  }

  /**
   * Every stored field decrypted and checked against what was signed for it.
   *
   * <p>The other half of verifying. {@link #brokenValues()} and {@link #firstBrokenEntry()} check
   * the digests, which cover commitments rather than ciphertext and so need only the root; this
   * checks each ciphertext against its commitment, which needs the keys. A ciphertext copied in
   * from another row is found here, and by any read of it, and nowhere else.
   *
   * <p>What was <i>altered</i> is proof of tampering. What was <i>unreadable</i> would not decrypt
   * with the keys at hand -- a destroyed key, or a damaged ciphertext -- and only whoever manages
   * the keys can say which.
   */
  public Sweep sweep() {
    return verification.sweep();
  }
}
