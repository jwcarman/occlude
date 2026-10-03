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

/**
 * Every reason a refused line can give.
 *
 * <p>Each portal's result has its own enum, such as {@link Revealed.Reason}, which lists only what
 * that portal can say. This enum lists them all, and adds the reasons that belong to no single
 * portal: what storage found, and why a value could not be occluded. A line stores the reason as
 * its {@link #name()}, in the clear, so an alert can match on it without decrypting anything.
 */
public enum RefusalReason {
  /** A derivation was given nothing to work from. */
  NO_PARENTS,
  /** No such value. Also what a manufactured id gets. */
  NO_SUCH_VALUE,
  /** The value is not the type the portal takes. */
  WRONG_TYPE,
  /** The value's label is above the portal's ceiling. */
  ABOVE_CEILING,
  /** The portal is not offered in this context. */
  NOT_AVAILABLE_HERE,
  /** A privileged derivation's relabelling did not lower anything. */
  NOT_A_LOWERING,
  /** The registered function refused, on its own terms. */
  DECLINED,
  /** The registered function threw after it was handed the plaintext. */
  FAILED,
  /** The erasure's policy did not permit it. */
  NOT_PERMITTED,
  /** What the store holds is not what it signed. */
  NOT_AS_SIGNED,
  /** The store could not read back what it holds. */
  UNREADABLE,
  /** The source could not say how to label the value that arrived. */
  SOURCE_CANNOT_LABEL,
  /** The source's label leaves a required axis unsaid. */
  INCOMPLETE_LABEL;

  /**
   * The refusal reason of the same name as a portal's own reason.
   *
   * <p>{@code RefusalReasonTest} checks that every portal's reason has one.
   */
  static RefusalReason of(Enum<?> portalReason) {
    return valueOf(portalReason.name());
  }
}
