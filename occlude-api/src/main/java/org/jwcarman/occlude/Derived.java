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
package org.jwcarman.occlude;

import java.util.Optional;

/**
 * What came of asking for a value to be derived from another.
 *
 * @param <O> the type of the value that would be made
 */
public sealed interface Derived<O> {

  /**
   * A new handle, stored, labelled and with its lineage recorded.
   *
   * @param occluded the new value's handle
   * @param <O> the new value's type
   */
  record Made<O>(Occluded<O> occluded) implements Derived<O> {}

  /**
   * No new value, and why.
   *
   * @param reason why, as a code that names a rule and never a value
   * @param detail what the code leaves out, for whoever handles the refusal
   * @param <O> the type the value would have had
   */
  record Refused<O>(Reason reason, String detail) implements Derived<O> {}

  /** Why a derivation did not happen. */
  enum Reason {
    /** A derivation was given nothing to work from. */
    NO_PARENTS,
    /** No such parent value. */
    NO_SUCH_VALUE,
    /** The parent is not the type this derivation takes. */
    WRONG_TYPE,
    /**
     * The parent's label is above what this derivation accepts. A derivation receives plaintext in
     * order to compute, so it is a sink like any other.
     */
    ABOVE_CEILING,
    /** This derivation is not offered in this context. */
    NOT_AVAILABLE_HERE,
    /**
     * A privileged derivation's relabelling did not actually lower anything. Raising is what
     * ordinary derivation already does, so declaring it here is a mistake worth naming.
     */
    NOT_A_LOWERING,
    /** The registered function refused, on its own terms. */
    DECLINED
  }

  /**
   * The new handle when it was made, empty when it was not.
   *
   * @return the new handle, or empty on a refusal
   */
  default Optional<Occluded<O>> value() {
    return this instanceof Made<O>(Occluded<O> occluded) ? Optional.of(occluded) : Optional.empty();
  }

  /**
   * Whether it was made.
   *
   * @return true when a new value was made
   */
  default boolean succeeded() {
    return this instanceof Made<O>;
  }

  /**
   * The handle, or an exception naming the refusal.
   *
   * @return the new handle
   */
  default Occluded<O> orThrow() {
    if (this instanceof Made<O>(Occluded<O> occluded)) {
      return occluded;
    }
    Refused<O> refused = (Refused<O>) this;
    throw new DerivationRefusedException(refused.reason(), refused.detail());
  }
}
