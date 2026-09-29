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

import java.util.OptionalInt;

/**
 * What came of asking to forget a value.
 *
 * <p>A result rather than an exception, like every other operation's: a value already gone and a
 * policy that said no are outcomes a caller handles, and both are in the record either way.
 */
public sealed interface Erased {

  /** The value and everything derived from it are gone. */
  record Removed(int count) implements Erased {}

  /** Nothing was removed, and why. */
  record Refused(Reason reason, String detail) implements Erased {}

  /** Why nothing was removed. */
  enum Reason {
    /** No such value. Also what a manufactured id gets. */
    NO_SUCH_VALUE,
    /** The erasure's policy did not say yes, or could not decide. */
    NOT_PERMITTED
  }

  /** How many values were removed, the root included, or empty when nothing was. */
  default OptionalInt removed() {
    return this instanceof Removed removed ? OptionalInt.of(removed.count()) : OptionalInt.empty();
  }

  default boolean succeeded() {
    return this instanceof Removed;
  }

  /** How many values were removed, or an exception naming the refusal. */
  default int orThrow() {
    if (this instanceof Removed removed) {
      return removed.count();
    }
    Refused refused = (Refused) this;
    throw new AccessDeniedException(refused.reason().name(), refused.detail());
  }
}
