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

import org.jwcarman.occlude.lattice.Ceiling;

/**
 * Reading what a value is labelled and where it came from, without the value.
 *
 * <p>The order below is the contract: a value not held, then a ceiling that cannot be evaluated,
 * then the ceiling itself. Every outcome is recorded, because a label is what storage encrypts and
 * what a refusal refuses to repeat -- reading one is an access like any other.
 */
final class Inspecting {

  private final Gate gate;
  private final Trail trail;
  private final Storage storage;

  Inspecting(Gate gate, Trail trail, Storage storage) {
    this.gate = gate;
    this.trail = trail;
    this.storage = storage;
  }

  Inspected inspect(Occluded<?> held, InspectionSpec inspection) {
    AccessContext asking = gate.asking();
    String by = inspection.name();
    StoredMetadata entry =
        trail
            .reading(
                AuditRecord.Operation.INSPECT,
                held.id(),
                by,
                asking,
                () -> storage.metadata(held.id()))
            .orElse(null);
    if (entry == null) {
      trail.audit(
          AuditRecord.Operation.INSPECT,
          held.id(),
          by,
          AuditRecord.Outcome.REFUSED,
          Why.of(Inspected.Reason.NO_SUCH_VALUE.name()),
          null,
          asking);
      return new Inspected.Refused(Inspected.Reason.NO_SUCH_VALUE, Gate.NOT_HOLDING + held.id());
    }
    Ceiling ceiling = gate.ceilingOf(() -> inspection.ceilingFor(asking));
    if (ceiling == null) {
      trail.audit(
          AuditRecord.Operation.INSPECT,
          held.id(),
          by,
          AuditRecord.Outcome.REFUSED,
          Why.of(Inspected.Reason.ABOVE_CEILING.name()),
          entry.label(),
          asking);
      return new Inspected.Refused(
          Inspected.Reason.ABOVE_CEILING, "'" + by + Gate.COULD_NOT_SAY_WHAT_IT_ACCEPTS);
    }
    if (!gate.admits(ceiling, entry.label())) {
      trail.audit(
          AuditRecord.Operation.INSPECT,
          held.id(),
          by,
          AuditRecord.Outcome.REFUSED,
          Why.of(Inspected.Reason.ABOVE_CEILING.name(), Gate.because(entry.label(), ceiling)),
          entry.label(),
          asking);
      return new Inspected.Refused(
          Inspected.Reason.ABOVE_CEILING, held.id() + " may not be inspected by '" + by + "'");
    }
    trail.audit(
        AuditRecord.Operation.INSPECT,
        held.id(),
        by,
        AuditRecord.Outcome.ALLOWED,
        Why.nothing(),
        entry.label(),
        asking);
    return new Inspected.Seen(entry.label(), entry.lineage());
  }
}
