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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;

/**
 * Handing plaintext to a sink.
 *
 * <p>The order below is the contract: an unknown sink, then a value not held, then a ceiling that
 * cannot be evaluated, then the ceiling itself -- and <b>only then</b> the type. A reader who may
 * not see a value must not learn what kind of value it is. Audit, then decode.
 */
final class Revealing {

  private final Gate gate;
  private final Trail trail;
  private final Storage storage;
  private final Map<String, SinkSpec> sinks;

  Revealing(Gate gate, Trail trail, Storage storage, List<SinkSpec> declared) {
    this.gate = gate;
    this.trail = trail;
    this.storage = storage;
    Map<String, SinkSpec> byId = new LinkedHashMap<>();
    for (SinkSpec sink : declared) {
      if (byId.put(sink.name(), sink) != null) {
        throw new IllegalStateException("two sinks are registered as '" + sink.name() + "'");
      }
    }
    this.sinks = Collections.unmodifiableMap(byId);
  }

  <T> Revealed<T> reveal(Occluded<T> held, OccludedType<T> expected, String to) {
    AccessContext context = gate.asking();
    SinkSpec sink = sinks.get(to);
    if (sink == null) {
      return denied(
          Revealed.Reason.NO_SUCH_SINK,
          "no sink is registered as '" + to + "'",
          null,
          held.id(),
          to,
          null,
          context);
    }
    StoredMetadata entry = storage.metadata(held.id()).orElse(null);
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
    Ceiling ceiling = gate.ceilingOf(sink, context);
    if (ceiling == null) {
      return denied(
          Revealed.Reason.ABOVE_CEILING,
          "'" + to + Gate.COULD_NOT_SAY_WHAT_IT_ACCEPTS,
          null,
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
    trail.audit(
        AuditRecord.Operation.REVEAL,
        held.id(),
        to,
        AuditRecord.Outcome.ALLOWED,
        Why.nothing(),
        entry.label(),
        context);
    // The type was confirmed against what the store wrote, so this decodes a verified fact.
    return storage
        .value(held.id(), expected.type())
        .<Revealed<T>>map(Revealed.Allowed::new)
        .orElseGet(
            () ->
                new Revealed.Denied<>(Revealed.Reason.NO_SUCH_VALUE, Gate.NOT_HOLDING + held.id()));
  }

  private <T> Revealed<T> denied(
      Revealed.Reason reason,
      String detail,
      String because,
      String value,
      String target,
      Label label,
      AccessContext context) {
    trail.audit(
        AuditRecord.Operation.REVEAL,
        value,
        target,
        AuditRecord.Outcome.REFUSED,
        Why.of(reason.name(), because),
        label,
        context);
    return new Revealed.Denied<>(reason, detail);
  }
}
