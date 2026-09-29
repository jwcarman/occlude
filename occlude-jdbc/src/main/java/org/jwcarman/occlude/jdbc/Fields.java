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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecException;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TransientCodecException;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.versioned.VersionedFormatException;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.Lineage;
import org.jwcarman.occlude.storage.StorageIntegrityException;
import org.jwcarman.occlude.storage.StorageUnreadableException;
import org.jwcarman.occlude.storage.StoredMetadata;

/**
 * What a stored field becomes on its way to disk and back: serialised, encrypted, and on the way
 * back decrypted and checked against what was signed for it before anything uses it.
 */
final class Fields {

  private final CodecFactory codecs;

  private final Codec<byte[]> storageCodec;
  private final PayloadKeys payloadKeys;

  /**
   * Serialisation alone, for the two things stored as a map of strings: a label, and whatever the
   * application calls identity. Kept apart from the encryption because what is committed to is the
   * plaintext these produce, and what is written is that plaintext encrypted.
   */
  private final Codec<Map<String, String>> stringMaps;

  private final Axes axes;

  private final Map<String, Codec<?>> byType = new ConcurrentHashMap<>();

  private final Signer signer;

  Fields(
      CodecFactory codecs,
      Codec<byte[]> storageCodec,
      PayloadKeys payloadKeys,
      Axes axes,
      Signer signer) {
    this.codecs = codecs;
    this.storageCodec = storageCodec;
    this.payloadKeys = payloadKeys;
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

  /** A map serialised by {@link #serialisedMap}, back. */
  Map<String, String> mapOf(byte[] serialised) {
    return stringMaps.decode(serialised);
  }

  /** Text a line stored as UTF-8. */
  static String text(byte[] utf8) {
    return new String(utf8, StandardCharsets.UTF_8);
  }

  /** Through the pipeline. */
  byte[] encrypt(byte[] plaintext) {
    return storageCodec.encode(plaintext);
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
  Label labelOf(ResultSet rows, List<String> parents) throws SQLException {
    return Label.decode(stringMaps.decode(labelPlaintextOf(rows, parents)), axes);
  }

  /**
   * A row's label, decrypted and checked -- together with its type, derivation and parents, which
   * the caller read from the same place and is about to act on.
   */
  byte[] labelPlaintextOf(ResultSet rows, List<String> parents) throws SQLException {
    byte[] plaintext =
        opened(
            rows.getBytes(Columns.LABEL),
            "the label stored for " + rows.getString(Columns.VALUE_ID));
    List<byte[]> facts =
        Signer.labelFacts(
            rows.getString(Columns.VALUE_TYPE), rows.getString(Columns.DERIVATION), parents);
    faithful(rows, Signer.LABEL, facts, plaintext, rows.getBytes(Columns.LABEL_COMMITMENT));
    return plaintext;
  }

  /** A row's payload, decrypted and checked against what was signed for it. */
  byte[] payloadOf(ResultSet rows) throws SQLException {
    byte[] plaintext =
        opened(
            payloadCodec(rows),
            rows.getBytes(Columns.PAYLOAD),
            "the payload stored for " + rows.getString(Columns.VALUE_ID));
    List<byte[]> facts = Signer.payloadFacts(rows.getString(Columns.VALUE_TYPE));
    faithful(rows, Signer.PAYLOAD, facts, plaintext, rows.getBytes(Columns.PAYLOAD_COMMITMENT));
    return plaintext;
  }

  private void faithful(
      ResultSet rows, String field, List<byte[]> facts, byte[] plaintext, byte[] committed)
      throws SQLException {
    String id = rows.getString(Columns.VALUE_ID);
    MacAlgorithm algorithm = MacAlgorithm.named(rows.getString(Columns.MAC));
    String root = rows.getString(Columns.ROOT_ID);
    if (algorithm == null
        || !matches(
            () -> signer.valueCommitment(root, algorithm, id, field, facts, plaintext),
            committed)) {
      throw new StorageIntegrityException(
          "the " + field + " stored for " + id + " is not what was signed for it");
    }
  }

  /** Through the pipeline, keeping an absent field absent. */
  byte[] encrypted(byte[] plaintext) {
    return plaintext == null ? null : storageCodec.encode(plaintext);
  }

  /**
   * Out of the pipeline, or a refusal that says which kind.
   *
   * <p>Bytes that are not a frame this store writes are somebody else's, so that is tampering. A
   * frame that will not decrypt is only unreadable: a destroyed key looks exactly like a damaged
   * ciphertext, and only whoever manages the keys can tell them apart. A key service that could not
   * be reached says nothing about the row at all: that is an outage, reported like a database that
   * is down, and never recorded against the value or filed as unreadable by a sweep.
   */
  byte[] opened(byte[] ciphertext, String what) {
    return opened(storageCodec, ciphertext, what);
  }

  /** The same, through a codec other than the shared one -- a tenant's, for a payload. */
  private static byte[] opened(Codec<byte[]> codec, byte[] ciphertext, String what) {
    try {
      return codec.decode(ciphertext);
    } catch (TransientCodecException e) {
      throw new IllegalStateException(what + " could not be opened: its key was out of reach", e);
    } catch (VersionedFormatException _) {
      throw new StorageIntegrityException(what + " is not a frame this store writes");
    } catch (CodecException e) {
      throw new StorageUnreadableException(what + " would not decrypt with the keys at hand", e);
    }
  }

  /**
   * A payload, encrypted under the keys its label chooses: its tenant's when the store is keyed by
   * one, the shared ones otherwise.
   */
  byte[] encryptPayload(byte[] plaintext, Label label) {
    return payloadKeys.forLabel(label).encode(plaintext);
  }

  /**
   * The codec a row's payload was written through. For a keyed store that is chosen by the row's
   * label, so the label is opened and checked first -- which is why it lives under the shared keys.
   */
  private Codec<byte[]> payloadCodec(ResultSet rows) throws SQLException {
    return payloadKeys.keyed()
        ? payloadKeys.forLabel(labelOf(rows, parentsIn(rows)))
        : storageCodec;
  }

  /** The same, keeping an absent field absent. */
  byte[] openedIfPresent(byte[] ciphertext, String what) {
    return ciphertext == null ? null : opened(ciphertext, what);
  }

  /**
   * Whether a commitment recomputes to what is stored.
   *
   * <p>False, never an exception, when it cannot be recomputed at all -- a root nobody supplies, or
   * one too short to trust -- because that is a finding about the row, and letting it throw turned
   * one rewritten root_id into a sweep that reported nothing for the whole store.
   */
  private static boolean matches(Supplier<byte[]> commitment, byte[] committed) {
    try {
      return MessageDigest.isEqual(commitment.get(), committed);
    } catch (IllegalStateException _) {
      return false;
    }
  }

  /**
   * The parent ids a row came with, from the correlated {@code parents} column every metadata query
   * selects -- one round trip, where reading them separately was one more per row.
   */
  static List<String> parentsIn(ResultSet rows) throws SQLException {
    Array parents = rows.getArray(PARENTS);
    if (parents == null) {
      return List.of();
    }
    return Arrays.stream((Object[]) parents.getArray()).map(String.class::cast).toList();
  }

  /** The column holding a row's parent ids, in order. */
  static final String PARENTS = "parents";

  /** Selects a value row's parent ids, in order, as {@link #PARENTS}; the row must be aliased v. */
  static final String PARENTS_OF_V =
      "(SELECT array_agg(parent_id ORDER BY position) FROM occlude_lineage l"
          + " WHERE l.child_id = v.value_id) AS "
          + PARENTS;

  /**
   * Refuses unless a row's digest still agrees with what it commits to and with its parents'
   * digests.
   *
   * <p>For erasure, which must not trust the lineage it walks: a forged lineage row would let an
   * erasure of one value take an unrelated one with it, and record that as lawful. The digest
   * covers the parents, so a row given a parent it never had fails here. Needs the root and never a
   * key, so erasing what a destroyed key protected is not blocked by it.
   */
  void requireSigned(ResultSet rows) throws SQLException {
    String id = rows.getString(Columns.VALUE_ID);
    Array stored = rows.getArray("parent_digests");
    List<byte[]> parents =
        stored == null
            ? List.of()
            : Arrays.stream((Object[]) stored.getArray()).map(byte[].class::cast).toList();
    Optional<byte[]> computed =
        parents.stream().anyMatch(Objects::isNull)
            ? Optional.empty()
            : signer.digestIfSigned(
                rows.getString(Columns.ROOT_ID),
                rows.getString(Columns.MAC),
                id,
                rows.getString(Columns.VALUE_TYPE),
                new Signer.ValueCommitments(
                    rows.getBytes(Columns.PAYLOAD_COMMITMENT),
                    rows.getBytes(Columns.LABEL_COMMITMENT)),
                rows.getString(Columns.DERIVATION),
                parents);
    if (computed.isEmpty()
        || !MessageDigest.isEqual(computed.get(), rows.getBytes(Columns.DIGEST))) {
      throw new StorageIntegrityException(
          id + " is not what was signed for it, so nothing that reaches it may be erased");
    }
  }

  /** A line's protected fields, decrypted and checked against the commitment signed for them. */
  record Line(byte[] detail, byte[] label, byte[] context) {

    @Override
    public boolean equals(Object other) {
      return other instanceof Line(byte[] thatDetail, byte[] thatLabel, byte[] thatContext)
          && Arrays.equals(detail, thatDetail)
          && Arrays.equals(label, thatLabel)
          && Arrays.equals(context, thatContext);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          Arrays.hashCode(detail), Arrays.hashCode(label), Arrays.hashCode(context));
    }

    @Override
    public String toString() {
      // Never the fields: a detail names the label a refusal turned away.
      return "Line";
    }
  }

  /**
   * One line's detail, label and context, decrypted and checked, from a row carrying them with its
   * entry id, predecessor, time, commitment, root and MAC.
   *
   * @throws StorageIntegrityException if they are not what was signed for that line
   */
  Line lineOf(ResultSet rows) throws SQLException {
    String what = "line " + rows.getLong(Columns.ENTRY_ID) + " of the trail";
    byte[] detail = openedIfPresent(rows.getBytes(Columns.DETAIL), what);
    byte[] label = openedIfPresent(rows.getBytes(Columns.LABEL), what);
    byte[] context = opened(rows.getBytes(Columns.CONTEXT), what);
    MacAlgorithm algorithm = MacAlgorithm.named(rows.getString(Columns.MAC));
    String root = rows.getString(Columns.ROOT_ID);
    byte[] previous = rows.getBytes("previous");
    Instant recordedAt = rows.getTimestamp("recorded_at").toInstant();
    if (algorithm == null
        || !matches(
            () ->
                signer.lineCommitment(
                    root, algorithm, previous, recordedAt, detail, label, context),
            rows.getBytes(Columns.COMMITMENT))) {
      throw new StorageIntegrityException(
          "line " + rows.getLong(Columns.ENTRY_ID) + " of the trail is not what was signed for it");
    }
    return new Line(detail, label, context);
  }

  /** One row's type, checked label and lineage. */
  StoredMetadata metadataOf(ResultSet rows) throws SQLException {
    String derivation = rows.getString(Columns.DERIVATION);
    // Read for every value, fresh ones included: a lineage row added to a value that has none would
    // otherwise go unchecked, and it decides what an erasure of its "parent" takes with it.
    List<String> parents = parentsIn(rows);
    Label label = labelOf(rows, parents);
    Lineage lineage =
        derivation == null ? Lineage.occluded() : Lineage.derivedFrom(parents, derivation);
    return new StoredMetadata(rows.getString(Columns.VALUE_TYPE), label, lineage);
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
