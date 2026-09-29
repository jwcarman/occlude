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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.StorageIntegrityException;

/**
 * The trail, read back: what happened to a value, or in a window of time, as the record says.
 *
 * <p>This is an authority, and a large one. Every line carries its label and who was asking,
 * decrypted -- across every value and every tenant, which is exactly what an {@code Inspection}
 * guards one value at a time. So it is handed to whoever investigates, and to nothing else; the
 * Spring starter registers it by name and hides it from injection by type, as it does the store.
 *
 * <p>Every line is checked before it is returned: its digest against what it says, its commitment
 * against what it decrypts to, and the line it was signed after against the one actually before it
 * -- a line's place is not itself signed, so two lines swapped would otherwise each still verify.
 * One that fails is refused rather than reported as a fact. Whether lines are <i>missing</i> -- cut
 * from the middle or the end -- is a question about the chain as a whole, which {@link
 * StorageIntegrity#check()} answers.
 */
public final class AuditTrail {

  private static final String COLUMNS =
      """
      SELECT entry_id, recorded_at, operation, value_id, target, outcome, reason, previous, digest,
             root_id, mac, commitment, detail, label, context,
             (SELECT p.digest FROM occlude_audit p WHERE p.entry_id < a.entry_id
               ORDER BY p.entry_id DESC LIMIT 1) AS predecessor
        FROM occlude_audit a
      """;

  private final DataSource dataSource;
  private final Signer signer;
  private final Fields fields;

  AuditTrail(DataSource dataSource, Signer signer, Fields fields) {
    this.dataSource = dataSource;
    this.signer = signer;
    this.fields = fields;
  }

  /**
   * Every line about one value, in the order they were recorded.
   *
   * <p>Found by the value id each line names -- a column in the clear, so a line whose id somebody
   * rewrote is not found here. {@link StorageIntegrity#check()} reports that line broken.
   */
  public List<RecordedLine> about(String valueId) {
    Objects.requireNonNull(valueId, "lines about some value");
    return lines(COLUMNS + " WHERE value_id = ? ORDER BY entry_id", valueId);
  }

  /** Every line recorded from {@code from}, inclusive, to {@code to}, exclusive, in order. */
  public List<RecordedLine> between(Instant from, Instant to) {
    Objects.requireNonNull(from, "a window starts somewhere");
    Objects.requireNonNull(to, "a window ends somewhere");
    return lines(
        COLUMNS + " WHERE recorded_at >= ? AND recorded_at < ? ORDER BY entry_id",
        Timestamp.from(from),
        Timestamp.from(to));
  }

  /**
   * Up to {@code limit} lines after {@code entryId}, in order: the way to walk the whole trail a
   * page at a time, starting from {@code 0}.
   */
  public List<RecordedLine> after(long entryId, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("a page holds at least one line");
    }
    return lines(COLUMNS + " WHERE entry_id > ? ORDER BY entry_id LIMIT ?", entryId, limit);
  }

  private List<RecordedLine> lines(String sql, Object... parameters) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      List<RecordedLine> lines = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          lines.add(recorded(rows));
        }
      }
      return lines;
    } catch (SQLException e) {
      throw new IllegalStateException("could not read the trail back", e);
    }
  }

  private RecordedLine recorded(ResultSet rows) throws SQLException {
    long entry = rows.getLong(Columns.ENTRY_ID);
    Instant recordedAt = rows.getTimestamp("recorded_at").toInstant();
    Signer.LineFacts facts =
        new Signer.LineFacts(
            rows.getString("operation"),
            rows.getString(Columns.VALUE_ID),
            rows.getString("target"),
            rows.getString("outcome"),
            rows.getString("reason"));
    Optional<byte[]> expected =
        signer.lineDigestIfSigned(
            rows.getString(Columns.ROOT_ID),
            rows.getString(Columns.MAC),
            rows.getBytes("previous"),
            recordedAt,
            facts,
            rows.getBytes(Columns.COMMITMENT));
    if (expected.isEmpty()
        || !MessageDigest.isEqual(expected.get(), rows.getBytes(Columns.DIGEST))) {
      throw new StorageIntegrityException(
          "line " + entry + " of the trail is not what was signed for it");
    }
    if (!Arrays.equals(rows.getBytes("previous"), rows.getBytes("predecessor"))) {
      throw new StorageIntegrityException(
          "line "
              + entry
              + " of the trail is not where it was signed: the line before it is not the one it"
              + " names");
    }
    Fields.Line line = fields.lineOf(rows);
    return new RecordedLine(
        entry,
        recordedAt,
        new AuditRecord(
            AuditRecord.Operation.valueOf(facts.operation()),
            facts.value(),
            Optional.ofNullable(facts.target()),
            AuditRecord.Outcome.valueOf(facts.outcome()),
            Optional.ofNullable(facts.reason()),
            Optional.ofNullable(line.detail()).map(Fields::text),
            Optional.ofNullable(line.label()).map(Fields::text),
            fields.mapOf(line.context())));
  }
}
