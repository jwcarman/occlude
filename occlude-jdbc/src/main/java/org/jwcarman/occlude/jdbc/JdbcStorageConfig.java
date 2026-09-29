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

import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.crypto.DataKeyProvider;
import org.jwcarman.codec.crypto.EnvelopeCodec;
import org.jwcarman.codec.versioned.VersionedCodec;
import org.jwcarman.occlude.Charter;
import org.jwcarman.occlude.lattice.Axes;

/**
 * What a database-backed store needs that has nothing to do with policy.
 *
 * <p>Deliberately not a kind of {@link Charter}. An application declares what it allows -- the
 * labels, the doors, who may reach them -- without knowing or caring where the values end up, and
 * the code declaring portals should compile against the generic thing. This is the other half:
 * where the tables are, how values are serialised, whose keys encrypt them, and what the record is
 * signed under. Both are asked for the {@link JdbcStorage} a charter is bound to.
 *
 * <p><b>Nothing here is optional that protects anything.</b> Every value, label, audit detail and
 * audit context is encrypted, and the record and the values are signed under a secret root. There
 * is no plaintext mode and no default secret, because a store that could be built without either
 * would be built without either.
 */
public final class JdbcStorageConfig {

  /**
   * The pipeline version every payload is written under. Fixed forever: version 1 is codec's
   * envelope over the configured keys and nothing else. A later pipeline -- padding, a new
   * algorithm -- is added as 2 beside it, so what is already stored still reads.
   */
  static final int ENVELOPE = 1;

  private DataSource dataSource;
  private CodecFactory codecs;
  private DataKeyProvider dataKeys;
  private String rootId;
  private Function<String, byte[]> roots;
  private MacAlgorithm mac = MacAlgorithm.HMAC_SHA256;
  private boolean migrate = true;

  /** Where the tables are. */
  public JdbcStorageConfig dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "a durable store needs a data source");
    return this;
  }

  /** How values become bytes. */
  public JdbcStorageConfig codecs(CodecFactory codecs) {
    this.codecs = Objects.requireNonNull(codecs, "a durable store needs codecs");
    return this;
  }

  /**
   * Whose keys encrypt what this stores.
   *
   * <p>Each payload gets a fresh AES-256-GCM data key, wrapped under the provider's current
   * key-encryption key and recorded with that key's id, so rotating keys is adding one and making
   * it current: what is already stored still decrypts under the id it names. The provider is the
   * application's -- {@code JceDataKeyProvider} over keys it holds, or a KMS -- and this module
   * never reads, generates or stores key material.
   *
   * <p>No compression, deliberately. Compressing before encrypting makes the ciphertext's length
   * depend on what the plaintext says, which is the side channel CRIME and BREACH exploit, and a
   * security library has no size to save that is worth it.
   */
  public JdbcStorageConfig encryptedWith(DataKeyProvider dataKeys) {
    this.dataKeys = Objects.requireNonNull(dataKeys, "a durable store needs keys to encrypt with");
    return this;
  }

  /**
   * What new values and lines are signed with. HMAC-SHA-256 unless said otherwise.
   *
   * <p>Recorded on every row, so changing it takes effect for what comes next and leaves what was
   * already signed verifying under the algorithm it names. Pair a change with a new root.
   */
  public JdbcStorageConfig signedWith(MacAlgorithm mac) {
    this.mac = Objects.requireNonNull(mac, "a store signs with some algorithm");
    return this;
  }

  /** Leaves the tables alone, for somewhere that manages its own schema. */
  public JdbcStorageConfig withoutMigration() {
    this.migrate = false;
    return this;
  }

  /**
   * What a fresh value hashes from, having no parents of its own.
   *
   * <p>Every value carries a digest over its own bytes and its parents', so editing one breaks
   * everything derived from it. Where that stops being merely expensive is here. Rooted in a
   * published constant, somebody with write access could recompute a graph after editing it -- an
   * edit still visible to anyone holding an earlier copy of a digest, but one that could be covered
   * up. Given a secret this database does not hold, no node can be forged at all. That is why there
   * is no default: a store used to be rooted in the constant {@code "occlude"} unless told
   * otherwise, and was forgeable by anyone who could write its tables.
   *
   * <p>A lookup rather than a value, so the secret can come from wherever secrets come from and
   * need never be written down beside the thing it protects.
   *
   * <p>Named, because a root that cannot be rotated is one nobody will rotate. Each value and each
   * line records which root it was written under, and verifying asks for that one -- so a new root
   * takes effect for what comes next without invalidating everything already stored. The same shape
   * the payload codec uses for its keys, and the id is signed as well, so two stores sharing a
   * secret still produce different digests.
   *
   * <p>Make the lookup a closed set: the ids you have issued, and nothing else. It is asked for
   * whatever root id a stored row names, and anyone who can write the tables chooses that name -- a
   * lookup that fetched any name it was given from a secret manager would fetch on their say-so.
   */
  public JdbcStorageConfig rootedIn(String id, Function<String, byte[]> roots) {
    this.rootId = Objects.requireNonNull(id, "a root needs a name");
    this.roots = Objects.requireNonNull(roots, "a root must not be null");
    return this;
  }

  /** The same, for an application that has only ever had one root. */
  public JdbcStorageConfig rootedIn(String id, byte[] secret) {
    Objects.requireNonNull(secret, "a root needs a secret");
    requireStrong(id, secret);
    byte[] only = secret.clone();
    return rootedIn(id, asked -> id.equals(asked) ? only : null);
  }

  /**
   * The storage a charter with these axes is bound to.
   *
   * <p>The only way to build one, and it refuses until it has been told where the tables are, how
   * values are serialised, whose keys encrypt them and what they are signed under.
   */
  public JdbcStorage storage(Axes axes) {
    Objects.requireNonNull(axes, "a durable store needs the charter's axes");
    JdbcStorage storage =
        JdbcStorage.of(
            require(dataSource, "a durable store needs a data source: call dataSource(...)"),
            require(
                codecs,
                "a durable store needs codecs: give it a CodecFactory that can serialise values"),
            pipeline(
                require(
                    dataKeys,
                    "a durable store encrypts everything it keeps: call encryptedWith(...) with a"
                        + " DataKeyProvider -- a JceDataKeyProvider over keys you hold, or your"
                        + " KMS")),
            axes,
            require(
                rootId,
                "a durable store signs its record and its values under a secret it does not hold:"
                    + " call rootedIn(...)"),
            roots,
            mac);
    // Checked here as well as when set, so a lookup that cannot supply the current root, or
    // supplies
    // a short one, stops the store from being built rather than every write failing afterwards.
    requireStrong(rootId, roots.apply(rootId));
    if (migrate) {
      storage.migrate();
    }
    return storage;
  }

  /**
   * Every byte on its way to disk: the envelope, inside a version header naming it.
   *
   * <p>The header is outermost so that everything inside it may change: a payload says which
   * pipeline wrote it, and a later one is introduced beside it rather than over it.
   */
  static Codec<byte[]> pipeline(DataKeyProvider dataKeys) {
    return VersionedCodec.<byte[]>builder()
        .version(ENVELOPE, EnvelopeCodec.builder(dataKeys).build())
        .writing(ENVELOPE)
        .build();
  }

  /** Whether a root's secret is long enough to be one: a chain is exactly as strong as its key. */
  private static void requireStrong(String id, byte[] secret) {
    if (secret == null || secret.length < Signer.MINIMUM_ROOT_BYTES) {
      throw new IllegalStateException(
          "the root '"
              + id
              + "' needs a secret of at least "
              + Signer.MINIMUM_ROOT_BYTES
              + " bytes -- a random one, kept outside this database");
    }
  }

  private static <T> T require(T value, String said) {
    if (value == null) {
      throw new IllegalStateException(said);
    }
    return value;
  }
}
