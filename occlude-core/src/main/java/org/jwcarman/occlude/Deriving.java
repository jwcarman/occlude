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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;

/**
 * Making a value from others: every derivation and every fold, run positionally.
 *
 * <p>The order below is the contract. What needs neither a ceiling nor a parent is refused first.
 * The ceiling is resolved once, before any parent is touched and before any type check. Each parent
 * is then checked in order -- held, admitted, then its type -- and plaintext is read only after
 * every parent has passed. The function runs; null is declining and throwing is a refusal. Last, a
 * relabelling is checked: null, then not a lowering, then leaving a required axis unsaid.
 */
final class Deriving {

  private final Gate gate;
  private final Trail trail;
  private final Storage storage;

  Deriving(Gate gate, Trail trail, Storage storage) {
    this.gate = gate;
    this.trail = trail;
    this.storage = storage;
  }

  /**
   * Every derivation and every fold, run positionally.
   *
   * <p>The parents' types and their number were settled by the capability the caller held, so this
   * does not re-derive them -- it checks each parent against the type declared for its position,
   * which is the one thing the compiler could not know: a {@link String} that arrived as text can
   * be given any type by {@link Occluded#of}, so what the store wrote remains the only ground
   * truth.
   */
  <O> Derived<O> derive(DerivationSpec<O> spec, List<Occluded<?>> parents) {
    AccessContext asking = gate.asking();
    AtomicReference<Label> label = new AtomicReference<>();
    AtomicReference<String> because = new AtomicReference<>();
    Derived<O> result = deriving(spec, parents, asking, label, because);
    if (result instanceof Derived.Refused<O> refused) {
      trail.audit(
          AuditRecord.Operation.DERIVE,
          parents.isEmpty() ? storage.freshId() : parents.getFirst().id(),
          spec.name(),
          AuditRecord.Outcome.REFUSED,
          Why.of(refused.reason().name(), because.get()),
          label.get(),
          asking);
    }
    return result;
  }

  private <O> Derived<O> deriving(
      DerivationSpec<O> spec,
      List<Occluded<?>> parents,
      AccessContext context,
      AtomicReference<Label> refused,
      AtomicReference<String> because) {
    String id = spec.name();
    Derived.Refused<O> unusable = unusable(spec, parents, context);
    if (unusable != null) {
      return unusable;
    }
    // Once, not once per parent: a ceiling that reads ambient context is doing real work. Before
    // any parent is looked at, and deliberately before the type check: a caller who may not reach
    // a value must not learn what kind of value it is.
    Ceiling ceiling = gate.ceilingOf(() -> spec.ceilingFor(context));
    if (ceiling == null) {
      return new Derived.Refused<>(
          Derived.Reason.ABOVE_CEILING, "'" + id + Gate.COULD_NOT_SAY_WHAT_IT_ACCEPTS);
    }

    List<String> parentIds = parents.stream().map(Occluded::id).toList();
    Map<String, StoredMetadata> labels =
        trail.reading(
            AuditRecord.Operation.DERIVE,
            parentIds.getFirst(),
            id,
            context,
            () -> storage.metadata(parentIds));
    Vetted<O> vetted = vetting(spec, parents, labels, ceiling, refused, because);
    if (vetted.refusal() != null) {
      return vetted.refusal();
    }

    // Only now, and only for parents every check above let through.
    Map<String, Object> plaintext =
        trail.reading(
            AuditRecord.Operation.DERIVE,
            parentIds.getFirst(),
            id,
            context,
            () -> storage.values(vetted.wanted()));
    Read<O> read = reading(parents, plaintext);
    if (read.refusal() != null) {
      return read.refusal();
    }

    Produced<O> produced = producing(spec, read.inputs(), context);
    if (produced.refusal() != null) {
      return produced.refusal();
    }
    if (produced.value().isEmpty()) {
      return new Derived.Refused<>(Derived.Reason.DECLINED, "'" + id + "' declined");
    }

    Relabelled<O> relabelled = relabelling(spec, vetted.joined(), because);
    if (relabelled.refusal() != null) {
      return relabelled.refusal();
    }
    return writing(
        spec, produced.value().get(), relabelled.label(), vetted.joined(), parentIds, context);
  }

  /**
   * The refusals that need neither a ceiling nor a parent: nothing to read, and not offered here.
   *
   * <p>{@code null} means nothing here refused it. The arity mismatch in the middle is not a
   * refusal at all -- a fixed-arity capability cannot be called with the wrong number of handles,
   * so reaching it means the spec and the capability that declared it disagree. That is a bug here,
   * not there.
   */
  private <O> Derived.Refused<O> unusable(
      DerivationSpec<O> spec, List<Occluded<?>> parents, AccessContext context) {
    String id = spec.name();
    if (parents.isEmpty()) {
      return new Derived.Refused<>(
          Derived.Reason.NO_PARENTS, "'" + id + "' needs at least one value");
    }
    if (!spec.fold() && parents.size() != spec.inputTypes().size()) {
      throw new IllegalStateException(
          "'%s' reads %d values and was given %d"
              .formatted(id, spec.inputTypes().size(), parents.size()));
    }
    if (!gate.offeredHere(() -> spec.availableTo().test(context))) {
      return new Derived.Refused<>(
          Derived.Reason.NOT_AVAILABLE_HERE, "'" + id + "' is not offered here");
    }
    return null;
  }

  /**
   * What checking the parents produced: what may now be decoded, and the label the result carries
   * -- or the refusal that stopped it, in which case neither of the others was reached.
   */
  private record Vetted<O>(
      Map<String, TypeRef<?>> wanted, Label joined, Derived.Refused<O> refusal) {

    static <O> Vetted<O> refusing(Derived.Reason reason, String detail) {
      return new Vetted<>(null, null, new Derived.Refused<>(reason, detail));
    }
  }

  /**
   * Every parent's label and type, against the position it was handed in at.
   *
   * <p>Labels first, for every parent at once, and no plaintext anywhere near this. A fold over ten
   * parents used to be ten round trips here and ten more below; it is one and one.
   *
   * <p>The ceiling is checked before the type, for the same reason it is checked before anything: a
   * caller who may not reach a value learns nothing about it beyond that.
   */
  private <O> Vetted<O> vetting(
      DerivationSpec<O> spec,
      List<Occluded<?>> parents,
      Map<String, StoredMetadata> labels,
      Ceiling ceiling,
      AtomicReference<Label> refused,
      AtomicReference<String> because) {
    String id = spec.name();
    Map<String, TypeRef<?>> wanted = new LinkedHashMap<>();
    Label joined = null;
    for (int position = 0; position < parents.size(); position++) {
      Occluded<?> parent = parents.get(position);
      // A fold applies its one type to every parent; a derivation has one per position.
      OccludedType<?> expected =
          spec.fold() ? spec.inputTypes().getFirst() : spec.inputTypes().get(position);
      StoredMetadata entry = labels.get(parent.id());
      if (entry == null) {
        return Vetted.refusing(Derived.Reason.NO_SUCH_VALUE, Gate.NOT_HOLDING + parent.id());
      }
      if (!gate.admits(ceiling, entry.label())) {
        because.set(Gate.because(entry.label(), ceiling));
        return Vetted.refusing(
            Derived.Reason.ABOVE_CEILING, parent.id() + " may not reach '" + id + "'");
      }
      if (!entry.typeName().equals(expected.name())) {
        return Vetted.refusing(
            Derived.Reason.WRONG_TYPE,
            "'%s' reads a %s in position %d, but %s is a %s"
                .formatted(id, expected.name(), position + 1, parent.id(), entry.typeName()));
      }
      wanted.put(parent.id(), expected.type());
      // Every parent contributes. This is the line that makes a mixed-tenant value unusable.
      joined = joined == null ? entry.label() : joined.join(entry.label());
      refused.set(joined);
    }
    return new Vetted<>(wanted, joined, null);
  }

  /** The plaintext, in the order the parents were handed in -- or the refusal instead of it. */
  private record Read<O>(List<Object> inputs, Derived.Refused<O> refusal) {}

  /** Decodes only what every check above let through, and refuses a parent erased meanwhile. */
  private <O> Read<O> reading(List<Occluded<?>> parents, Map<String, Object> read) {
    List<Object> inputs = new ArrayList<>();
    for (Occluded<?> parent : parents) {
      Object input = read.get(parent.id());
      if (input == null) {
        return new Read<>(
            null,
            new Derived.Refused<>(Derived.Reason.NO_SUCH_VALUE, Gate.NOT_HOLDING + parent.id()));
      }
      inputs.add(input);
    }
    return new Read<>(inputs, null);
  }

  /**
   * Runs the application's function over the plaintext.
   *
   * <p>Three things it can do, two answers. Returning a value is the ordinary one. Returning an
   * empty {@link Optional} is declining, which is its right. Returning {@code null} has broken its
   * own contract, and is normalised here to declining rather than checked later. Throwing is the
   * remaining one, and becomes a refusal that says so.
   *
   * <p>None of them may end as a NullPointerException thrown out of the library, past the audit,
   * after the value was read. By this point the function has been handed the plaintext, so every
   * way it can end has to be something the trail can record.
   */
  private <O> Produced<O> producing(
      DerivationSpec<O> spec, List<Object> inputs, AccessContext context) {
    try {
      return new Produced<>(
          Objects.requireNonNullElse(
              spec.function().apply(List.copyOf(inputs), context), Optional.empty()),
          null);
    } catch (RuntimeException _) {
      // It has already seen the plaintext, so this refusal has to be recorded like any other.
      return new Produced<>(
          Optional.empty(),
          new Derived.Refused<>(
              Derived.Reason.DECLINED, "'" + spec.name() + "' failed while reading the value"));
    }
  }

  /**
   * What the derivation produced, or the refusal that says it failed while holding the plaintext.
   *
   * <p>Two different answers, so two different fields. An empty {@code value} means the function
   * declined, which is its right; a non-null {@code refusal} means it threw. Carrying both in one
   * nullable Optional would make "declined" and "broke" the same answer to anyone who forgot the
   * null check, and this is the one place in the library holding a decoded value.
   */
  private record Produced<O>(Optional<O> value, Derived.Refused<O> refusal) {}

  /**
   * The label the result will carry, or the refusal that says the relabelling was not a lowering.
   */
  private record Relabelled<O>(Label label, Derived.Refused<O> refusal) {

    static <O> Relabelled<O> refusing(String detail) {
      return new Relabelled<>(null, new Derived.Refused<>(Derived.Reason.NOT_A_LOWERING, detail));
    }
  }

  /**
   * What a privileged derivation asked for, checked against what it was allowed to ask for.
   *
   * <p>An ordinary derivation carries the join and comes straight back out. A privileged one has to
   * name a label at or below it, and has to leave no required axis unsaid.
   */
  private <O> Relabelled<O> relabelling(
      DerivationSpec<O> spec, Label joined, AtomicReference<String> because) {
    String id = spec.name();
    if (spec.relabel() == null) {
      return new Relabelled<>(joined, null);
    }
    Label label;
    try {
      label = spec.relabel().apply(joined);
    } catch (RuntimeException _) {
      label = null;
    }
    // Answering with nothing is not answering, and is the same event as throwing. The plaintext has
    // already been read, so this has to be a recorded refusal rather than a NullPointerException
    // thrown past the audit.
    if (label == null) {
      return Relabelled.refusing("'" + id + "' could not say what it was lowering to");
    }
    if (!label.atOrBelow(joined)) {
      because.set(Gate.because(label, joined));
      return Relabelled.refusing("'%s' relabelled a value as something not below it".formatted(id));
    }
    // atOrBelow cannot see this one. An axis a label stops mentioning joins as bottom, so a label
    // that drops one is at or below everything -- including the label it came from. The check that
    // runs at the door has to run here too, because this is the other way a value can come to exist
    // with a required axis missing.
    if (gate.leavesARequiredAxisUnsaid(label)) {
      because.set(Gate.because(label, joined));
      return Relabelled.refusing(
          ("'%s' relabelled a value so that a required axis is unsaid. Unsaid is the bottom of its"
                  + " order, which is below every ceiling, so the result would have been readable"
                  + " by everyone.")
              .formatted(id));
    }
    return new Relabelled<>(label, null);
  }

  /**
   * What a successful derivation's record says beyond the bare fact.
   *
   * <p>Weakening a label is the event an auditor is looking for, so it is always said. Combining
   * several values is worth noting because the result is more constrained than any one parent.
   * Deriving one value from one is the ordinary case and says nothing extra.
   */
  private static String reasonFor(DerivationSpec<?> spec, Label joined, int parents) {
    if (spec.privileged()) {
      return "weakened from " + joined;
    }
    return parents > 1 ? "combined from " + parents + " values" : null;
  }

  /** The child and the line saying it was made, written as one act. */
  private <O> Derived<O> writing(
      DerivationSpec<O> spec,
      O value,
      Label label,
      Label joined,
      List<String> parentIds,
      AccessContext context) {
    String id = spec.name();
    String newId = storage.freshId();
    AuditRecord entry =
        trail.entry(
            AuditRecord.Operation.DERIVE,
            newId,
            id,
            AuditRecord.Outcome.ALLOWED,
            Why.of(reasonFor(spec, joined, parentIds.size())),
            label,
            context);
    try {
      storage.put(
          newId,
          new StoredValue(value, spec.outputType(), label, Lineage.derivedFrom(parentIds, id)),
          entry);
    } catch (RuntimeException _) {
      // A parent can be erased while the derivation function is running: every check passed, the
      // plaintext was read, and by the time the child is written its ancestry is gone. The read
      // happened, so this is a refusal that has to be recorded, not an exception that escapes
      // past the audit -- the same rule that covers application code failing after it has seen
      // the value.
      trail.audit(
          AuditRecord.Operation.DERIVE,
          newId,
          id,
          AuditRecord.Outcome.REFUSED,
          Why.of("could not be written"),
          label,
          context);
      return new Derived.Refused<>(
          Derived.Reason.NO_SUCH_VALUE, "'" + id + "' could not be completed");
    }
    return new Derived.Made<>(new Occluded<>(newId));
  }
}
