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

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jwcarman.occlude.AuditRecord;

/**
 * Every MAC a store computes: the commitments to what things say, and the digests over them.
 *
 * <p>One place for the keying, the domain separation and the encoding, so the value graph, the
 * trail and the commitments cannot drift apart. Keyed by a root this database does not hold, which
 * is why none of it can be recomputed by whoever can write the tables.
 */
final class Signer {

  /** What a commitment is to, so a label's cannot be passed off as a payload's. */
  static final String PAYLOAD = "payload";

  static final String LABEL = "label";

  private final String rootId;

  private final Function<String, byte[]> roots;

  private final MacAlgorithm mac;

  Signer(String rootId, Function<String, byte[]> roots, MacAlgorithm mac) {
    this.rootId = rootId;
    this.roots = roots;
    this.mac = mac;
  }

  /** The root new values and lines are signed under. */
  String rootId() {
    return rootId;
  }

  /** What new values and lines are signed with. */
  MacAlgorithm mac() {
    return mac;
  }

  /**
   * What kind of thing is being signed.
   *
   * <p>One key signs both the value graph and the trail. Without a tag they share a MAC, so a
   * construction in one is a construction in the other. Fed before anything an attacker influences,
   * so no value's bytes can ever be read back as a line's.
   */
  enum Domain {
    VALUE,
    LINE,
    VALUE_COMMITMENT,
    LINE_COMMITMENT
  }

  /**
   * The least a root may be. A keyed chain is exactly as strong as the secret keying it, and 32
   * bytes is what HMAC-SHA-256 was built to take; anything shorter makes forging a search.
   */
  static final int MINIMUM_ROOT_BYTES = 32;

  /**
   * Keyed by a root, which is why none of this can be recomputed by whoever can write.
   *
   * <p>Named, so rotating a root does not invalidate what was written under the last one. The id is
   * signed too, so two stores sharing a secret still produce different digests.
   */
  Mac keyed(Domain domain, String id, MacAlgorithm algorithm) {
    byte[] secret = roots.apply(id);
    // Empty is as good as absent -- and left alone it would not even reach the MAC: SecretKeySpec
    // throws IllegalArgumentException on it, which the verifier does not expect, so a lookup that
    // answered with nothing would crash the report instead of marking the row broken.
    if (secret == null || secret.length == 0) {
      throw new IllegalStateException(
          "nothing supplies the root '" + id + "', which some of this was written under");
    }
    if (secret.length < MINIMUM_ROOT_BYTES) {
      throw new IllegalStateException(
          "the root '" + id + "' is shorter than " + MINIMUM_ROOT_BYTES + " bytes");
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

  /** How many of whatever follows, so two different shapes cannot feed the same bytes. */
  static void feedCount(Mac mac, int count) {
    mac.update(
        new byte[] {
          (byte) (count >>> 24), (byte) (count >>> 16), (byte) (count >>> 8), (byte) count
        });
  }

  static void feed(Mac mac, byte[] field) {
    int length = field == null ? -1 : field.length;
    mac.update(
        new byte[] {
          (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length
        });
    if (field != null) {
      mac.update(field);
    }
  }

  /** What a value's payload and label commit to, fed to its digest in that order. */
  record ValueCommitments(byte[] payload, byte[] label) {

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
   *
   * <p>And bound to the facts a read acts on beside the field: see {@link #payloadFacts} and {@link
   * #labelFacts}. Those columns sit in the clear next to the ciphertext, and a read that trusted
   * them unchecked could be steered by whoever rewrote them.
   */
  byte[] valueCommitment(
      String under,
      MacAlgorithm algorithm,
      String id,
      String field,
      List<byte[]> facts,
      byte[] plaintext) {
    Mac commitment = keyed(Domain.VALUE_COMMITMENT, under, algorithm);
    feed(commitment, id.getBytes(UTF_8));
    feed(commitment, field.getBytes(UTF_8));
    feedCount(commitment, facts.size());
    for (byte[] fact : facts) {
      feed(commitment, fact);
    }
    feed(commitment, plaintext);
    return commitment.doFinal();
  }

  /**
   * What a payload's commitment binds besides the bytes: the type it was stored as.
   *
   * <p>Every operation takes the stored type name as ground truth -- a reader declared for notes
   * decodes whatever says it is a note -- so a row retyped underneath its commitment must not read.
   */
  static List<byte[]> payloadFacts(String type) {
    return List.of(type.getBytes(UTF_8));
  }

  /**
   * What a label's commitment binds besides the bytes: the type, what made the value, and from
   * which parents, in order.
   *
   * <p>All of it comes back with the label on every metadata read, and all of it is acted on: the
   * type decides which readers may decode, and the parentage decides what an erasure reaches.
   */
  static List<byte[]> labelFacts(String type, String derivation, List<String> parents) {
    List<byte[]> facts = new ArrayList<>();
    facts.add(type.getBytes(UTF_8));
    facts.add(derivation == null ? null : derivation.getBytes(UTF_8));
    parents.forEach(parent -> facts.add(parent.getBytes(UTF_8)));
    return facts;
  }

  /** A keyed commitment to a line's protected fields, bound to its place in the chain. */
  byte[] lineCommitment(
      String under,
      MacAlgorithm algorithm,
      byte[] previous,
      Instant recordedAt,
      byte[] detail,
      byte[] label,
      byte[] context) {
    Mac commitment = keyed(Domain.LINE_COMMITMENT, under, algorithm);
    feed(commitment, previous);
    feed(commitment, recordedAt.toString().getBytes(UTF_8));
    feed(commitment, detail);
    feed(commitment, label);
    feed(commitment, context);
    return commitment.doFinal();
  }

  /**
   * The digest, or empty when this row cannot be checked at all.
   *
   * <p>A row naming a root nothing supplies is a finding, not a crash. Letting that throw handed an
   * attacker a way to turn "broken at this value" into "the verifier does not run": rewrite one
   * root_id and the whole report becomes an exception. It is also what an ordinary rotation looks
   * like once an old root is retired.
   */
  Optional<byte[]> digestIfSigned(
      String under,
      String algorithm,
      String id,
      String type,
      ValueCommitments commitments,
      String derivation,
      List<byte[]> parents) {
    // A row naming a MAC this store will not verify with, or a root nothing supplies, is broken
    // -- and says so as an absence. It used to be an empty digest, which a row whose own digest had
    // been blanked matched exactly: rewrite the MAC name, clear the digest, and the verifier called
    // any row intact.
    MacAlgorithm named = MacAlgorithm.named(algorithm);
    if (named == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(digestOf(under, named, id, type, commitments, derivation, parents));
    } catch (IllegalStateException _) {
      return Optional.empty();
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
  byte[] digestOf(
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
   * The fields of one line that are not already parameters in their own right: bundled so the
   * method that signs a line stays under the parameter count this project holds every method to,
   * without changing which bytes are fed or in what order.
   */
  record LineFacts(String operation, String value, String target, String outcome, String reason) {

    static LineFacts of(AuditRecord entry) {
      return new LineFacts(
          entry.operation().name(),
          entry.value(),
          entry.target().orElse(null),
          entry.outcome().name(),
          entry.reason().orElse(null));
    }
  }

  byte[] lineDigest(
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
  Optional<byte[]> lineDigestIfSigned(
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
}
