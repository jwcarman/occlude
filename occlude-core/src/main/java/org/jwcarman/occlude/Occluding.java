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

import java.util.function.BiFunction;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.Lineage;
import org.jwcarman.occlude.storage.Storage;
import org.jwcarman.occlude.storage.StoredValue;

/**
 * Taking a value in through a source.
 *
 * <p>The order below is the contract, not an implementation detail: a null value is rejected before
 * anything is recorded; a label that could not be decided, then one that leaves a required axis
 * unsaid, is a recorded refusal; and only then is an id minted and the value stored.
 */
final class Occluding {

  private final Gate gate;
  private final Trail trail;
  private final Storage storage;

  Occluding(Gate gate, Trail trail, Storage storage) {
    this.gate = gate;
    this.trail = trail;
    this.storage = storage;
  }

  /**
   * Holding through a door, which is holding without being told a label.
   *
   * <p>No {@code mayHold} check, because there is nothing left to check. That policy existed to
   * police a label the caller supplied; a door's label is a property of the door, decided during
   * configuration, and the caller contributes nothing to it.
   */
  <T> Occluded<T> occlude(
      String source, OccludedType<T> type, BiFunction<T, AccessContext, Label> labelling, T value) {
    if (value == null) {
      throw new IllegalArgumentException("a store holds values, not nulls");
    }
    AccessContext asking = gate.asking();
    Gate.Consulted<Label> consulted = Gate.Consulted.asking(() -> labelling.apply(value, asking));
    Label label = consulted.answer();
    if (label == null) {
      String detail = "'" + source + "' could not say what it labels values" + consulted.threw();
      trail.audit(
          AuditRecord.Operation.CONCEAL,
          storage.freshId(),
          source,
          AuditRecord.Outcome.REFUSED,
          Why.of("the source could not say how to label this", detail),
          null,
          asking);
      throw new RefusedException("SOURCE_CANNOT_LABEL", detail);
    }
    // One of two places a label can be incomplete. Join only moves up, so an ordinary derivation
    // cannot lose what was said here -- but a privileged one may relabel, so deriving checks too.
    if (gate.leavesARequiredAxisUnsaid(label)) {
      trail.audit(
          AuditRecord.Operation.CONCEAL,
          storage.freshId(),
          source,
          AuditRecord.Outcome.REFUSED,
          Why.of("the label leaves a required axis unsaid"),
          label,
          asking);
      throw new RefusedException(
          "INCOMPLETE_LABEL",
          ("'%s' produced a label that leaves a required axis unsaid. Unsaid is the bottom of its"
                  + " order, which is below every ceiling, so the value would have been readable"
                  + " by everyone.")
              .formatted(source));
    }
    String id = storage.freshId();
    // One act: the value and the record that it arrived. The source is named, so the record says
    // which door it came in through.
    AuditRecord entry =
        trail.entry(
            AuditRecord.Operation.CONCEAL,
            id,
            source,
            AuditRecord.Outcome.ALLOWED,
            Why.nothing(),
            label,
            asking);
    storage.put(id, new StoredValue(value, type, label, Lineage.occluded()), entry);
    return new Occluded<>(id);
  }
}
