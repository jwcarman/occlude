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
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.Lineage;

/** What came of asking what a value is labelled and where it came from. */
public sealed interface Inspected {

  /**
   * What the record says about the value.
   *
   * @param label what the value is labelled
   * @param lineage where it came from: its parents, and what made it from them
   */
  record Seen(Label label, Lineage lineage) implements Inspected {}

  /**
   * Nothing was read, and why.
   *
   * @param reason why, as a code that names a rule and never a value
   * @param detail what the code leaves out, for whoever handles the refusal
   */
  record Refused(Reason reason, String detail) implements Inspected {}

  /** Why nothing was read. */
  enum Reason {
    /** No such value. Also what a manufactured id gets. */
    NO_SUCH_VALUE,
    /** The label is above what this inspection accepts. The ordinary refusal. */
    ABOVE_CEILING
  }

  /**
   * What was seen, or empty when the inspection was refused.
   *
   * @return the label and lineage, or empty if refused
   */
  default Optional<Seen> value() {
    return this instanceof Seen seen ? Optional.of(seen) : Optional.empty();
  }

  /**
   * Whether something was read.
   *
   * @return true if the inspection was answered rather than refused
   */
  default boolean succeeded() {
    return this instanceof Seen;
  }

  /**
   * What was seen, or an exception naming the refusal.
   *
   * @return what was seen
   */
  default Seen orThrow() {
    if (this instanceof Seen seen) {
      return seen;
    }
    Refused refused = (Refused) this;
    throw new RefusedException(refused.reason().name(), refused.detail());
  }
}
