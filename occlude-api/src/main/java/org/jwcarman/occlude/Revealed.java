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
 * What came of asking for a value.
 *
 * <p>A refusal is an outcome rather than a fault, because callers respond to one differently.
 * Assembling a prompt treats a refusal as "render the handle instead" and carries on; a tool treats
 * it as a call that cannot proceed. Throwing would force the first caller to catch in its normal
 * path.
 *
 * @param <T> the type of the value asked for
 */
public sealed interface Revealed<T> {

  /**
   * The value, because the gate allowed it.
   *
   * @param plaintext the value itself
   * @param <T> its type
   */
  record Allowed<T>(T plaintext) implements Revealed<T> {

    /**
     * Says nothing about the value.
     *
     * <p>A record's generated {@code toString} would print it, and this object is exactly the sort
     * of thing that ends up in a log line or an exception message by accident. Getting the value
     * out should require asking for it.
     */
    @Override
    public String toString() {
      return "Allowed[plaintext=<held>]";
    }
  }

  /**
   * No value, and why.
   *
   * @param reason why, as a code that names a rule and never a value
   * @param detail what the code leaves out, for whoever handles the refusal
   * @param <T> the type the value would have had
   */
  record Denied<T>(Reason reason, String detail) implements Revealed<T> {}

  /** Why a value was not handed over. */
  enum Reason {
    /** No such value. Also what a manufactured id gets. */
    NO_SUCH_VALUE,
    /** The handle claimed a type the stored value does not have. */
    WRONG_TYPE,
    /** The label is above what this sink accepts. The ordinary refusal. */
    ABOVE_CEILING
  }

  /**
   * The value when the gate allowed it, empty when it did not.
   *
   * @return the value, or empty on a refusal
   */
  default Optional<T> value() {
    return this instanceof Allowed<T>(T plaintext) ? Optional.of(plaintext) : Optional.empty();
  }

  /**
   * Whether the gate allowed it.
   *
   * @return true when the value was handed over
   */
  default boolean succeeded() {
    return this instanceof Allowed<T>;
  }

  /**
   * The value, or an exception naming the refusal.
   *
   * <p>For code that genuinely cannot continue without it, and whose caller is not a prompt.
   *
   * @return the value
   */
  default T orThrow() {
    if (this instanceof Allowed<T>(T plaintext)) {
      return plaintext;
    }
    Denied<T> denied = (Denied<T>) this;
    throw new RefusedException(denied.reason(), denied.detail());
  }
}
