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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Encrypting everything a store holds again, under its current keys, so an old key can go. */
final class Reencryption {

  private final Transactions transactions;
  private final Fields fields;

  Reencryption(Transactions transactions, Fields fields) {
    this.transactions = transactions;
    this.fields = fields;
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
  int reencrypt() {
    int rewritten = 0;
    String afterValue = "";
    while (afterValue != null) {
      String from = afterValue;
      Page<String> page =
          transactions.inTransactionReturning(
              "could not re-encrypt values", connection -> reencryptValues(connection, from));
      rewritten += page.rewritten();
      afterValue = page.last();
    }
    Long afterLine = 0L;
    while (afterLine != null) {
      long from = afterLine;
      Page<Long> page =
          transactions.inTransactionReturning(
              "could not re-encrypt the trail", connection -> reencryptLines(connection, from));
      rewritten += page.rewritten();
      afterLine = page.last();
    }
    return rewritten;
  }

  /** One page of a re-encryption: how many rows it rewrote, and where the next one starts. */
  record Page<K>(int rewritten, K last) {}

  Page<String> reencryptValues(Connection connection, String after) throws SQLException {
    // Shared, as deriving takes it: an erasure waits for this page rather than deadlocking on the
    // rows it has locked, and derivations proceed alongside it.
    Transactions.lockLineageShared(connection);
    String last = null;
    int rewritten = 0;
    try (PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT value_id, value_type, derivation, payload, label, payload_commitment,
                       label_commitment, root_id, mac,
                       (SELECT array_agg(parent_id ORDER BY position) FROM occlude_lineage l
                         WHERE l.child_id = v.value_id) AS parents
                  FROM occlude_value v WHERE value_id > ? ORDER BY value_id LIMIT ? FOR UPDATE
                """);
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE occlude_value SET payload = ?, label = ? WHERE value_id = ?")) {
      select.setString(1, after);
      select.setInt(2, REENCRYPT_PAGE);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          last = rows.getString(Columns.VALUE_ID);
          List<String> parents = Fields.parentsIn(rows);
          update.setBytes(
              1, fields.encryptPayload(fields.payloadOf(rows), fields.labelOf(rows, parents)));
          update.setBytes(2, fields.encrypt(fields.labelPlaintextOf(rows, parents)));
          update.setString(3, last);
          update.addBatch();
          rewritten++;
        }
      }
      update.executeBatch();
    }
    return new Page<>(rewritten, rewritten < REENCRYPT_PAGE ? null : last);
  }

  Page<Long> reencryptLines(Connection connection, long after) throws SQLException {
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
          last = rows.getLong(Columns.ENTRY_ID);
          Fields.Line line = fields.lineOf(rows);
          update.setBytes(1, fields.encrypted(line.detail()));
          update.setBytes(2, fields.encrypted(line.label()));
          update.setBytes(3, fields.encrypt(line.context()));
          update.setLong(4, last);
          update.addBatch();
          rewritten++;
        }
      }
      update.executeBatch();
    }
    return new Page<>(rewritten, rewritten < REENCRYPT_PAGE ? null : last);
  }
}
