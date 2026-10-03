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

import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.Storage;
import org.jwcarman.occlude.storage.StoredMetadata;

/**
 * Answering one question about a value without the value leaving.
 *
 * <p>The order below is the contract: whether the question is offered to this caller at all, then a
 * value not held, then a ceiling that cannot be evaluated, then the ceiling itself -- and <b>only
 * then</b> the type, and only after that is the subject decoded.
 */
final class Querying {

  private final Gate gate;
  private final Trail trail;
  private final Storage storage;

  Querying(Gate gate, Trail trail, Storage storage) {
    this.gate = gate;
    this.trail = trail;
    this.storage = storage;
  }

  <I, Q> Answer ask(QuerySpec<I, Q> spec, Occluded<I> about, Q against) {
    AccessContext asking = gate.asking();
    AtomicReference<Label> label = new AtomicReference<>();
    AtomicReference<String> because = new AtomicReference<>();
    Answer answer = answering(spec, about, against, asking, label, because);
    if (answer instanceof Answer.Refused refused) {
      trail.audit(
          AuditRecord.Operation.QUERY,
          about.id(),
          spec.name(),
          AuditRecord.Outcome.REFUSED,
          Why.of(refused.reason().name(), because.get()),
          label.get(),
          asking);
    }
    return answer;
  }

  /**
   * What a refusal is allowed to say about labels, which by default is nothing.
   *
   * <p>{@code refused} and {@code because} are filled in only as far as the check got before saying
   * no, so the caller's audit line says exactly what was known at the point of refusal -- never
   * more, and never a label this call never actually looked at.
   */
  private <I, Q> Answer answering(
      QuerySpec<I, Q> spec,
      Occluded<I> held,
      Q against,
      AccessContext context,
      AtomicReference<Label> refused,
      AtomicReference<String> because) {
    String name = spec.name();
    Gate.Consulted<Boolean> offered = gate.offeredHere(() -> spec.availableTo().test(context));
    if (!offered.saidYes()) {
      because.set(offered.failure());
      return new Answer.Refused(
          Answer.Reason.NOT_AVAILABLE_HERE, "'" + name + "' is not offered here" + offered.threw());
    }
    StoredMetadata entry =
        trail
            .reading(
                AuditRecord.Operation.QUERY,
                held.id(),
                name,
                context,
                () -> storage.metadata(held.id()))
            .orElse(null);
    if (entry == null) {
      return new Answer.Refused(Answer.Reason.NO_SUCH_VALUE, Gate.NOT_HOLDING + held.id());
    }
    refused.set(entry.label());
    Gate.Consulted<Ceiling> consulted = gate.ceilingOf(() -> spec.ceilingFor(context));
    Ceiling ceiling = consulted.answer();
    if (ceiling == null) {
      because.set(consulted.failure());
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING,
          "'" + name + Gate.COULD_NOT_SAY_WHAT_IT_ACCEPTS + consulted.threw());
    }
    if (!gate.admits(ceiling, entry.label())) {
      because.set(Gate.because(entry.label(), ceiling));
      return new Answer.Refused(
          Answer.Reason.ABOVE_CEILING, held.id() + " may not be looked at by '" + name + "'");
    }
    if (!entry.typeName().equals(spec.inputType().name())) {
      return new Answer.Refused(
          Answer.Reason.WRONG_TYPE,
          "'%s' asks about a %s, but %s is a %s"
              .formatted(name, spec.inputType().name(), held.id(), entry.typeName()));
    }
    I subject =
        trail
            .reading(
                AuditRecord.Operation.QUERY,
                held.id(),
                name,
                context,
                () -> storage.value(held.id(), spec.inputType().type()))
            .orElse(null);
    if (subject == null) {
      return new Answer.Refused(Answer.Reason.NO_SUCH_VALUE, Gate.NOT_HOLDING + held.id());
    }
    boolean answer;
    try {
      answer = spec.asking().test(subject, against, context);
    } catch (RuntimeException e) {
      // It has already read the plaintext, so this refusal is recorded like any other.
      because.set(Gate.failure(e));
      return new Answer.Refused(
          Answer.Reason.FAILED, "'" + name + "' failed while reading the value" + Gate.threw(e));
    }
    // The answer, never what was asked: the argument can itself be sensitive.
    trail.audit(
        AuditRecord.Operation.QUERY,
        held.id(),
        name,
        AuditRecord.Outcome.ALLOWED,
        Why.of("answered " + answer),
        entry.label(),
        context);
    return new Answer.Answered(answer);
  }
}
