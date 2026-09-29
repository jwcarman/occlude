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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.occlude.Lineage;
import org.jwcarman.occlude.StoredMetadata;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Label;

/**
 * What a stored field becomes on its way to disk and back: serialised, encrypted, and on the way
 * back decrypted and checked against what was signed for it before anything uses it.
 */
final class Fields {

  private final CodecFactory codecs;

  private final Codec<byte[]> storageCodec;

  /**
   * Serialisation alone, for the two things stored as a map of strings: a label, and whatever the
   * application calls identity. Kept apart from the encryption because what is committed to is the
   * plaintext these produce, and what is written is that plaintext encrypted.
   */
  private final Codec<Map<String, String>> stringMaps;

  private final Axes axes;

  private final Map<String, Codec<?>> byType = new ConcurrentHashMap<>();

  private final Signer signer;

  Fields(CodecFactory codecs, Codec<byte[]> storageCodec, Axes axes, Signer signer) {
    this.codecs = codecs;
    this.storageCodec = storageCodec;
    this.axes = axes;
    this.signer = signer;
    // One axis at a time, keyed by name. A record would have gone to disk positionally, and then
    // declaring a fourth axis would make every row already written undecodable.
    this.stringMaps =
        codecs.create(TypeRef.mapOf(TypeRef.of(String.class), TypeRef.of(String.class)));
  }

  /** A map of strings -- a label, an access context -- as the plaintext that is committed to. */
  byte[] serialisedMap(Map<String, String> map) {
    return stringMaps.encode(map);
  }

  /** Through the pipeline. */
  byte[] encrypt(byte[] plaintext) {
    return storageCodec.encode(plaintext);
  }

  /** Back out of the pipeline. */
  byte[] decrypt(byte[] ciphertext) {
    return storageCodec.decode(ciphertext);
  }

  @SuppressWarnings("unchecked")
  byte[] serialised(TypeRef<?> type, Object value) {
    return ((Codec<Object>) serialiserFor(type)).encode(value);
  }

  /** How values of this type become bytes, before anything protects them. */
  @SuppressWarnings("unchecked")
  <T> Codec<T> serialiserFor(TypeRef<T> type) {
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
  Label labelOf(ResultSet rows) throws SQLException {
    return Label.decode(stringMaps.decode(labelPlaintextOf(rows)), axes);
  }

  byte[] labelPlaintextOf(ResultSet rows) throws SQLException {
    byte[] plaintext = storageCodec.decode(rows.getBytes(Columns.LABEL));
    faithful(rows, Signer.LABEL, plaintext, rows.getBytes(Columns.LABEL_COMMITMENT));
    return plaintext;
  }

  /** A row's payload, decrypted and checked against what was signed for it. */
  byte[] payloadOf(ResultSet rows) throws SQLException {
    byte[] plaintext = storageCodec.decode(rows.getBytes(Columns.PAYLOAD));
    faithful(rows, Signer.PAYLOAD, plaintext, rows.getBytes(Columns.PAYLOAD_COMMITMENT));
    return plaintext;
  }

  void faithful(ResultSet rows, String field, byte[] plaintext, byte[] committed)
      throws SQLException {
    String id = rows.getString(Columns.VALUE_ID);
    MacAlgorithm algorithm = MacAlgorithm.named(rows.getString(Columns.MAC));
    if (algorithm == null
        || !MessageDigest.isEqual(
            signer.valueCommitment(
                rows.getString(Columns.ROOT_ID), algorithm, id, field, plaintext),
            committed)) {
      throw new IllegalStateException(
          "the " + field + " stored for " + id + " is not what was signed for it");
    }
  }

  /** Through the pipeline, keeping an absent field absent. */
  byte[] encrypted(byte[] plaintext) {
    return plaintext == null ? null : storageCodec.encode(plaintext);
  }

  /** Back out of the pipeline, keeping an absent field absent. */
  byte[] decrypted(byte[] ciphertext) {
    return ciphertext == null ? null : storageCodec.decode(ciphertext);
  }

  /** One row's type, checked label and lineage. */
  StoredMetadata metadataOf(Connection connection, ResultSet rows) throws SQLException {
    String derivation = rows.getString(Columns.DERIVATION);
    Lineage lineage =
        derivation == null
            ? Lineage.occluded()
            : Lineage.derivedFrom(
                parentsOf(connection, rows.getString(Columns.VALUE_ID)), derivation);
    return new StoredMetadata(rows.getString(Columns.VALUE_TYPE), labelOf(rows), lineage);
  }

  private static final String SELECT_PARENTS =
      "SELECT parent_id FROM occlude_lineage WHERE child_id = ? ORDER BY position";

  static List<String> parentsOf(Connection connection, String id) throws SQLException {
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
}
