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

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;

/**
 * Every policy decision a store makes, and nothing else.
 *
 * <p>Shared by every operation, so an in-memory store and a durable one cannot disagree about who
 * may see what, and no two operations can disagree about what a label admits. Storage
 * implementations keep bytes; this decides. It records nothing: that is {@link Trail}'s job.
 */
final class Gate {

  static final String NOT_HOLDING = "this store is not holding ";
  static final String COULD_NOT_SAY_WHAT_IT_ACCEPTS =
      "' could not say what it accepts, so it does not accept this";

  private final Axes axes;
  private final AccessContextProvider ambient;
  private final Storage storage;

  Gate(Axes axes, AccessContextProvider ambient, Storage storage) {
    this.axes = axes;
    this.ambient = ambient;
    this.storage = storage;
  }

  /**
   * Whether a reader holding this ceiling may see a value labelled so.
   *
   * <p>Not simply {@link Ceiling#permits}, because a ceiling can only judge the axes a label speaks
   * to. An axis a label never mentions sits at the bottom of its order, and bottom is below every
   * ceiling -- so an incomplete label is not refused by everyone, it is admitted by everyone, which
   * is the exact opposite of what marking an axis required means.
   *
   * <p>Occluding refuses an incomplete label, and so does a lowering, but neither covers a row that
   * was already there: a store that adds {@code required()} to an axis it has been writing without,
   * or an SPI caller that put a value directly. Checking it here, where a stored label is read
   * back, is what makes the guarantee about values rather than about writes.
   */
  boolean admits(Ceiling ceiling, Label label) {
    return !leavesARequiredAxisUnsaid(label) && ceiling.permits(label);
  }

  /**
   * Whether a label leaves an axis unsaid that this store said it must not.
   *
   * <p>Checked at the one door a label is written through. Unsaid is the bottom of an axis's order,
   * which is below every ceiling, so a value that left a required axis unsaid would be readable by
   * everyone -- silently, and in the direction nobody would notice.
   */
  boolean leavesARequiredAxisUnsaid(Label label) {
    for (Axis<?> axis : axes) {
      if (label.unsaid(axis)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A ceiling is application code, and application code throws.
   *
   * <p>Treated as a refusal rather than allowed to propagate: a policy that cannot be evaluated has
   * not said yes, and a caller assembling a prompt should get a handle rather than a stack trace.
   */
  Ceiling ceilingOf(SinkSpec sink, AccessContext context) {
    try {
      return sink.ceiling(context);
    } catch (RuntimeException _) {
      return null;
    }
  }

  /**
   * The same treatment for a question's or a derivation's ceiling, which is equally application
   * code.
   *
   * <p>{@code null} means the ceiling did not decide, and every caller treats that as a refusal.
   * There is deliberately no second shape for "accepts anything": a derivation or a question
   * without a ceiling is refused at configuration, so a ceiling is always present and always has to
   * be consulted. This used to return an {@code Optional}, where empty was documented as accepting
   * anything -- nothing could produce that empty on purpose, but application code ending in {@code
   * .orElse(null)} produced it by accident, and the check was skipped.
   */
  Ceiling ceilingOf(Supplier<Ceiling> ceiling) {
    try {
      return ceiling.get();
    } catch (RuntimeException _) {
      return null;
    }
  }

  /** A gate that cannot say whether it is open has not said it is open. */
  boolean offeredHere(BooleanSupplier availableTo) {
    try {
      return availableTo.getAsBoolean();
    } catch (RuntimeException _) {
      return false;
    }
  }

  /**
   * Who is asking: what the store was told, with anything the caller added laid over it.
   *
   * <p>Resolved once per operation, because an ambient source may be doing real work to answer.
   */
  AccessContext asking() {
    return ambient.get();
  }

  /**
   * What the record says about a refusal, and what the caller never hears.
   *
   * <p>A refusal message that explains itself is an oracle: code that may not read a value could
   * still learn its classification by asking often enough and reading the answers. So the label and
   * the ceiling go to the audit, where they are protected like any other label, and the caller
   * learns which door said no and a coarse reason.
   */
  static String because(Label label, Object ceiling) {
    return "labelled " + label + "; accepts " + ceiling;
  }

  // ------------------------------------------------------------------ lookups, for administration

  Label label(String id) {
    return metadataOf(id).label();
  }

  Lineage lineage(String id) {
    return metadataOf(id).lineage();
  }

  private StoredMetadata metadataOf(String id) {
    return storage.metadata(id).orElseThrow(() -> new IllegalArgumentException(NOT_HOLDING + id));
  }
}
