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

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import javax.sql.DataSource;
import org.jwcarman.codec.CodecException;
import org.jwcarman.codec.versioned.VersionedFormatException;
import org.jwcarman.occlude.StorageIntegrityException;

/**
 * Whether what a store holds still agrees with what it signed: the trail, the value graph, and the
 * head somebody wrote down elsewhere. Needs the root, never a key -- which is why it still works
 * after a key has been destroyed.
 */
final class Verification {

  private final DataSource dataSource;
  private final Signer signer;
  private final Fields fields;

  Verification(DataSource dataSource, Signer signer, Fields fields) {
    this.dataSource = dataSource;
    this.signer = signer;
    this.fields = fields;
  }

  /**
   * Every stored field decrypted and checked against what was signed for it.
   *
   * <p>The digests cover commitments, not ciphertext, so {@link #brokenValues()} cannot see a
   * ciphertext swapped in from another row; a read refuses one, but only when somebody reads it.
   * This reads everything, which needs the keys.
   */
  Sweep sweep() {
    List<String> alteredValues = new ArrayList<>();
    List<String> unreadableValues = new ArrayList<>();
    List<Long> alteredLines = new ArrayList<>();
    List<Long> unreadableLines = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT value_id, value_type, derivation, payload, label, payload_commitment,
                         label_commitment, root_id, mac
                    FROM occlude_value ORDER BY value_id
                  """);
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String id = rows.getString(Columns.VALUE_ID);
          Finding finding =
              check(
                  () -> {
                    fields.payloadOf(rows);
                    fields.labelPlaintextOf(rows, Fields.parentsOf(connection, id));
                  });
          finding.file(id, alteredValues, unreadableValues);
        }
      }
      try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  SELECT entry_id, recorded_at, previous, detail, label, context, commitment,
                         root_id, mac
                    FROM occlude_audit ORDER BY entry_id
                  """);
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          check(() -> fields.lineOf(rows))
              .file(rows.getLong(Columns.ENTRY_ID), alteredLines, unreadableLines);
        }
      }
      return new Sweep(alteredValues, unreadableValues, alteredLines, unreadableLines);
    } catch (SQLException e) {
      throw new IllegalStateException("could not sweep the store", e);
    }
  }

  /** A check over one row that reads the database, so it may throw what reading does. */
  @FunctionalInterface
  private interface RowCheck {
    void run() throws SQLException;
  }

  /** What checking one row found. */
  private enum Finding {
    FAITHFUL,
    ALTERED,
    UNREADABLE;

    <K> void file(K key, List<K> altered, List<K> unreadable) {
      if (this == ALTERED) {
        altered.add(key);
      } else if (this == UNREADABLE) {
        unreadable.add(key);
      }
    }
  }

  /**
   * Whether a row's fields are what was signed for them.
   *
   * <p>Altered when they decrypt and fail their commitment, or are not a frame this store writes.
   * Unreadable when they will not decrypt -- which a destroyed key and a damaged ciphertext both
   * look like -- or were written by a pipeline newer than this one.
   */
  private static Finding check(RowCheck check) throws SQLException {
    try {
      check.run();
      return Finding.FAITHFUL;
    } catch (StorageIntegrityException | VersionedFormatException _) {
      return Finding.ALTERED;
    } catch (CodecException _) {
      return Finding.UNREADABLE;
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
  Optional<TrailHead> head() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT entry_id, digest FROM occlude_audit ORDER BY entry_id DESC LIMIT 1");
        ResultSet rows = statement.executeQuery()) {
      return rows.next()
          ? Optional.of(
              new TrailHead(rows.getLong(Columns.ENTRY_ID), rows.getBytes(Columns.DIGEST)))
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
  boolean stillHolds(TrailHead anchor) {
    Objects.requireNonNull(anchor, "an anchor must not be null");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT digest FROM occlude_audit WHERE entry_id = ?")) {
      statement.setLong(1, anchor.entryId());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() && MessageDigest.isEqual(rows.getBytes(Columns.DIGEST), anchor.digest());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not look for the anchor in the trail", e);
    }
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
  Optional<Long> firstBrokenEntry() {
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
  Optional<Long> firstBrokenIn(ResultSet rows) throws SQLException {
    byte[] expected = null;
    while (rows.next()) {
      byte[] previous = rows.getBytes("previous");
      if (!Arrays.equals(previous, expected)) {
        return Optional.of(rows.getLong(Columns.ENTRY_ID));
      }
      // A root nothing supplies is a broken line, not a crashed verifier. Left to throw, an
      // attacker who could edit a line could rewrite its root_id instead and turn "broken at
      // entry 7" into an exception that reports nothing at all.
      Signer.LineFacts facts =
          new Signer.LineFacts(
              rows.getString("operation"),
              rows.getString(Columns.VALUE_ID),
              rows.getString("target"),
              rows.getString("outcome"),
              rows.getString("reason"));
      // Commitments rather than ciphertext, so no key is needed here: the trail verifies even
      // after the key that encrypted a line has been destroyed.
      Optional<byte[]> digest =
          signer.lineDigestIfSigned(
              rows.getString(Columns.ROOT_ID),
              rows.getString(Columns.MAC),
              previous,
              rows.getTimestamp("recorded_at").toInstant(),
              facts,
              rows.getBytes(Columns.COMMITMENT));
      // Constant-time: the stored side is whatever a writer put there, and the computed side is a
      // MAC under the root, so a comparison that stops at the first difference is a timing oracle.
      if (digest.isEmpty() || !MessageDigest.isEqual(digest.get(), rows.getBytes(Columns.DIGEST))) {
        return Optional.of(rows.getLong(Columns.ENTRY_ID));
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
  List<String> missingValues() {
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
        missing.add(rows.getString(Columns.VALUE_ID));
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
  List<String> brokenValues() {
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
  record ValueRow(
      byte[] digest,
      Signer.ValueCommitments commitments,
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
                  Signer.ValueCommitments otherCommitments,
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

  Map<String, ValueRow> loadValueRows(ResultSet rows) throws SQLException {
    Map<String, ValueRow> byId = new LinkedHashMap<>();
    while (rows.next()) {
      String id = rows.getString(Columns.VALUE_ID);
      byId.put(
          id,
          new ValueRow(
              rows.getBytes(Columns.DIGEST),
              new Signer.ValueCommitments(
                  rows.getBytes(Columns.PAYLOAD_COMMITMENT),
                  rows.getBytes(Columns.LABEL_COMMITMENT)),
              rows.getString(Columns.DERIVATION),
              rows.getString(Columns.VALUE_TYPE),
              rows.getString(Columns.ROOT_ID),
              rows.getString(Columns.MAC)));
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
  List<String> verifyToFixpoint(Connection connection, Map<String, ValueRow> byId)
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
        Optional<byte[]> computed =
            signer.digestIfSigned(
                row.rootId(),
                row.mac(),
                id,
                row.type(),
                row.commitments(),
                row.derivation(),
                parents);
        // Unverifiable is broken. It is never "equal to an empty digest", which a writer could
        // arrange by blanking the stored one.
        if (computed.isPresent() && MessageDigest.isEqual(computed.get(), row.digest())) {
          seen.put(id, computed.get());
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
  Optional<List<byte[]>> checkableParents(
      Connection connection, String id, Map<String, byte[]> seen) throws SQLException {
    List<byte[]> parents = new ArrayList<>();
    for (String parent : Fields.parentsOf(connection, id)) {
      byte[] digest = seen.get(parent);
      if (digest == null) {
        return Optional.empty();
      }
      parents.add(digest);
    }
    return Optional.of(parents);
  }
}
