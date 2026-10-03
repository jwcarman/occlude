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
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.Storage;
import org.jwcarman.occlude.storage.StoredMetadata;

/**
 * Handing plaintext to a sink.
 *
 * <p>The order below is the contract: a value not held, then a ceiling that cannot be evaluated,
 * then the ceiling itself -- and <b>only then</b> the type. A reader who may not see a value must
 * not learn what kind of value it is. Then the value is read, and only once it is in hand is the
 * reveal recorded as allowed and handed over: a read that the store refuses is recorded as the
 * refusal it is, never as an allowed reveal that did not happen.
 */
final class Revealing {

  private final Gate gate;
  private final Trail trail;
  private final Storage storage;

  Revealing(Gate gate, Trail trail, Storage storage) {
    this.gate = gate;
    this.trail = trail;
    this.storage = storage;
  }

  /**
   * Hands the value to the sink the caller's portal was minted for.
   *
   * <p>The sink arrives whole, from the portal, rather than as a name to be looked up. A reader
   * therefore cannot name a sink that does not exist, and there is no refusal for one.
   */
  <T> Revealed<T> reveal(Occluded<T> held, OccludedType<T> expected, SinkSpec sink) {
    AccessContext context = gate.asking();
    String to = sink.name();
    StoredMetadata entry =
        trail
            .reading(
                AuditRecord.Operation.REVEAL,
                held.id(),
                to,
                context,
                () -> storage.metadata(held.id()))
            .orElse(null);
    if (entry == null) {
      return denied(
          Revealed.Reason.NO_SUCH_VALUE,
          Gate.NOT_HOLDING + held.id(),
          null,
          held.id(),
          to,
          null,
          context);
    }
    // The ceiling first, and the type only after it. A reader who may not see this value must not
    // learn what kind of value it is: "is a card, not an email" is the disclosure this library
    // exists to prevent, handed over in the refusal. Being told the wrong type is now something
    // only a reader already entitled to the value can be told.
    Gate.Consulted<Ceiling> consulted = gate.ceilingOf(sink, context);
    Ceiling ceiling = consulted.answer();
    if (ceiling == null) {
      return denied(
          Revealed.Reason.ABOVE_CEILING,
          "'" + to + Gate.COULD_NOT_SAY_WHAT_IT_ACCEPTS + consulted.threw(),
          consulted.failure(),
          held.id(),
          to,
          entry.label(),
          context);
    }
    if (!gate.admits(ceiling, entry.label())) {
      return denied(
          Revealed.Reason.ABOVE_CEILING,
          held.id() + " may not reach '" + to + "'",
          Gate.because(entry.label(), ceiling),
          held.id(),
          to,
          entry.label(),
          context);
    }
    if (!entry.typeName().equals(expected.name())) {
      return denied(
          Revealed.Reason.WRONG_TYPE,
          held.id() + " is a " + entry.typeName() + ", not a " + expected.name(),
          null,
          held.id(),
          to,
          entry.label(),
          context);
    }
    // The type was confirmed against what the store wrote, so this decodes a verified fact.
    Optional<T> value =
        trail.reading(
            AuditRecord.Operation.REVEAL,
            held.id(),
            to,
            context,
            () -> storage.value(held.id(), expected.type()));
    if (value.isEmpty()) {
      // Erased between the metadata and the value.
      return denied(
          Revealed.Reason.NO_SUCH_VALUE,
          Gate.NOT_HOLDING + held.id(),
          null,
          held.id(),
          to,
          entry.label(),
          context);
    }
    // Recorded before it is handed over, and only once it is in hand.
    trail.allowed(
        AuditRecord.Operation.REVEAL, held.id(), to, Why.nothing(), entry.label(), context);
    return new Revealed.Allowed<>(value.get());
  }

  private <T> Revealed<T> denied(
      Revealed.Reason reason,
      String detail,
      String because,
      String value,
      String target,
      Label label,
      AccessContext context) {
    trail.refused(
        AuditRecord.Operation.REVEAL,
        value,
        target,
        RefusalReason.of(reason),
        because,
        label,
        context);
    return new Revealed.Denied<>(reason, detail);
  }
}
