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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.crypto.DataKeyProvider;
import org.jwcarman.codec.crypto.DecryptionException;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Label;

/**
 * Which keys encrypt a value's payload: its tenant's, or the store's shared ones.
 *
 * <p>Chosen by the value's label, on the one axis the store was keyed by -- the value's own
 * statement of whose it is, fixed when it was stored, and the same thing isolation is defined on. A
 * label that says nothing on that axis, or a mixture of several, falls back to the shared keys.
 *
 * <p>Only payloads. A label is read to learn whose keys open the payload beside it, so a label
 * encrypted under those same keys could never be read at all; and the record must outlive any one
 * tenant's keys. Labels and every line of the trail stay under the shared keys.
 */
final class PayloadKeys {

  private final Codec<byte[]> shared;
  private final Axis<?> axis;
  private final Function<String, DataKeyProvider> keysFor;
  private final Map<String, Codec<byte[]>> byScope = new ConcurrentHashMap<>();

  private PayloadKeys(
      Codec<byte[]> shared, Axis<?> axis, Function<String, DataKeyProvider> keysFor) {
    this.shared = shared;
    this.axis = axis;
    this.keysFor = keysFor;
  }

  /** Every payload under the shared keys. */
  static PayloadKeys shared(Codec<byte[]> shared) {
    return new PayloadKeys(shared, null, null);
  }

  /** Payloads under the keys of whatever their label says on this axis. */
  static PayloadKeys keyedBy(
      Codec<byte[]> shared, Axis<?> axis, Function<String, DataKeyProvider> keysFor) {
    return new PayloadKeys(shared, axis, keysFor);
  }

  /** Whether a payload's keys depend on its label at all. */
  boolean keyed() {
    return axis != null;
  }

  /**
   * The codec for a value with this label.
   *
   * <p>A tenant nothing supplies keys for -- an offboarded one, whose key was destroyed -- gets a
   * codec that opens nothing and stores nothing. Reading their values is then unreadable, as a
   * destroyed key is everywhere else, so a sweep files them rather than stopping; and neither
   * refusal names the tenant, because a label's values are exactly what is kept out of messages.
   * Not remembered, so a tenant whose keys arrive later needs no restart.
   */
  Codec<byte[]> forLabel(Label label) {
    if (axis == null) {
      return shared;
    }
    return label.sole(axis).map(this::forScope).orElse(shared);
  }

  private Codec<byte[]> forScope(String scope) {
    Codec<byte[]> known = byScope.get(scope);
    if (known != null) {
      return known;
    }
    DataKeyProvider keys = keysFor.apply(scope);
    if (keys == null) {
      return NO_KEYS;
    }
    return byScope.computeIfAbsent(scope, ignored -> JdbcStorageConfig.pipeline(keys));
  }

  /** For a tenant nothing supplies keys for: opens nothing, stores nothing, names no one. */
  private static final Codec<byte[]> NO_KEYS =
      new Codec<>() {
        @Override
        public byte[] encode(byte[] plaintext) {
          throw new IllegalStateException(
              "nothing supplies keys for this value's payload, so it cannot be stored");
        }

        @Override
        public byte[] decode(byte[] ciphertext) {
          throw new DecryptionException("nothing supplies keys for this value's payload");
        }
      };
}
