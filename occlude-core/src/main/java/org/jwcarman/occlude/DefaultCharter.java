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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.manifest.Manifest;
import org.jwcarman.occlude.storage.Storage;

/**
 * Where an application declares its authority, and nothing more.
 *
 * <p>Every declaration hands back a portal that carries everything its operation needs. Binding the
 * charter to storage brings them all into force at once, and after that the charter has no further
 * part to play: the portals hold the operations, and nothing looks anything up by name.
 *
 * <p>The axes are a constructor argument because an axis supplied later could reorder what is
 * permitted underneath values already stored; the rest is declared on one thread while the
 * application wires itself, and refused once the charter is bound.
 */
public final class DefaultCharter implements Charter {

  private final Axes axes;
  private final Declarations declarations = new Declarations();
  private final Operations operations = new Operations();
  private final Portals portals = new Portals(operations);

  /**
   * The questions this charter asks about every value it holds.
   *
   * <p>Constructor arguments because they are not configuration -- they are what this charter is.
   * They decide what a label is able to say at all and what a stored row is decoded against, so a
   * charter cannot meaningfully exist before them.
   */
  public DefaultCharter(Axes axes) {
    this.axes = Objects.requireNonNull(axes, "a charter needs axes");
  }

  /** The same, for an application naming its axes inline rather than handing over a schema. */
  public DefaultCharter(Axis<?>... axes) {
    this(Axes.of(axes));
  }

  @Override
  public Axes axes() {
    return axes;
  }

  /**
   * Brings every portal this charter constituted into force at once, against this storage, with
   * identity coming from {@code currentAccess}.
   *
   * <p>Irreversible. After it returns, nothing further may be declared and everything already
   * declared works. There is no way back: no unbinding, no rebinding, no replacing the storage.
   *
   * <p>Where the access happening right now comes from is supplied here rather than declared,
   * because it is not authority: it is where identity lives in this environment, exactly as storage
   * is where values live.
   *
   * <p>Identity is known at the edge -- a request, a message, a session -- and needed at the gate,
   * which may be many layers down. Threading an {@code AccessContext} parameter through all of them
   * would make the safety feature the most annoying thing in the codebase, and annoying safety
   * features get routed around.
   *
   * <p>So the application says once where the answer lives. A {@code ThreadLocal}, a {@code
   * ScopedValue}, Spring's {@code SecurityContextHolder} -- a store does not care, and has no
   * opinion about how a request scope works.
   *
   * <p>Whatever this returns is taken as fact. It is the one input a caller cannot argue with,
   * which is why it must come from somewhere a caller does not control.
   *
   * <pre>{@code
   * charter.bind(storage, () -> AccessContext.of(Map.of(
   *     "tenant", CurrentTenant.get(),
   *     "principal", SecurityContextHolder.getContext().getAuthentication().getName()))))
   * }</pre>
   *
   * <p>An application with no notion of identity passes {@link AccessContextProvider#none()}, and
   * says so where it binds rather than getting it by forgetting.
   */
  public void bind(Storage storage, AccessContextProvider currentAccess) {
    Objects.requireNonNull(storage, "a charter is bound to a storage");
    Objects.requireNonNull(currentAccess, "a charter is told where identity comes from");
    synchronized (declarations) {
      operations.bind(axes, storage, currentAccess);
    }
  }

  /**
   * Records a declaration, unless this charter is already in force.
   *
   * <p>A security invariant rather than an ergonomic one: an authority graph that can still grow is
   * not one anybody can reason about.
   *
   * <p>Under the same lock as {@link #bind} and {@link #manifest(AccessContext)}. Declaring is
   * single-threaded by contract, but the contract used to be all that held: a declaration racing
   * bind on another thread could pass the check, lose the race, and mint a live portal no manifest
   * listed, and a manifest rendered on a request thread had nothing guaranteeing it saw what the
   * declaring thread wrote. Now a declaration either lands before binding, where the manifest sees
   * it, or is refused.
   */
  private void declaring(Runnable record) {
    synchronized (declarations) {
      if (operations.bound()) {
        throw new IllegalStateException(
            "nothing further can be declared: this charter has been bound, and an authority graph"
                + " that can still grow is not one anybody can reason about");
      }
      record.run();
    }
  }

  // ------------------------------------------------------------------ declaring capabilities

  /** A source whose label depends on neither what arrives nor who is acting. */
  @Override
  public <T> Occlude<T> source(String name, OccludedType<T> type, Label label) {
    Objects.requireNonNull(label, "a source needs a label");
    return source(name, type, (value, context) -> label);
  }

  /**
   * The same, for a label that does not depend on what is arriving.
   *
   * <p>The common case: a door knows what it is, so mail from customers is untrusted whatever it
   * says. Reach for the other form when the label is a property of the value -- a classification
   * marking inside a document, a sender the ingest verified, a scan that found card numbers.
   */
  @Override
  public <T> Occlude<T> source(
      String name, OccludedType<T> type, Function<AccessContext, Label> labelling) {
    Objects.requireNonNull(labelling, "a source needs to say how it labels what arrives");
    return source(name, type, (value, context) -> labelling.apply(context));
  }

  @Override
  public <T> Occlude<T> source(
      String name, OccludedType<T> type, BiFunction<T, AccessContext, Label> labelling) {
    Objects.requireNonNull(name, "a source needs a name");
    Objects.requireNonNull(type, "a source needs to know what it accepts");
    Objects.requireNonNull(labelling, "a source needs to say how it labels what arrives");
    declaring(() -> declarations.source(name, type));
    return portals.source(name, type, labelling);
  }

  /**
   * Declares somewhere values may go: what may reach it, and what it reads.
   *
   * <p>Both restrictions are settled here and neither can be widened afterwards. The ceiling says
   * which labels may arrive; {@code reads} says which types come back out. Because both are fixed
   * before any reader exists, a reader is a typed view rather than a grant -- which is what makes
   * one safe to constitute on demand, and the sink itself safe to hand to the service that talks to
   * that subsystem.
   *
   * @param reads every type this sink will hand over, and no others
   */
  @Override
  public Sink sink(
      String name, Function<AccessContext, Ceiling> ceiling, OccludedType<?>... reads) {
    Objects.requireNonNull(name, "a sink needs a name");
    Objects.requireNonNull(ceiling, "a sink needs a ceiling");
    if (reads.length == 0) {
      throw new IllegalStateException(
          "'"
              + name
              + "' has to say which types it reads. A sink that reads anything reads"
              + " everything its ceiling admits, including whatever gets stored at that label"
              + " next year.");
    }
    // Declaration order, not hash order: this list ends up in an error message somebody reads.
    Set<String> names =
        Arrays.stream(reads)
            .map(OccludedType::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    SinkSpec sink = Sinks.varying(name, ceiling);
    declaring(() -> declarations.sink(sink, names, reads));
    return portals.sink(sink, Collections.unmodifiableSet(names));
  }

  /** The same, for a ceiling that does not depend on who is asking. */
  @Override
  public Sink sink(String name, Ceiling ceiling, OccludedType<?>... reads) {
    Objects.requireNonNull(ceiling, "a sink needs a ceiling");
    return sink(name, context -> ceiling, reads);
  }

  /**
   * Mints the authority to make one value from one other. Configuration time only.
   *
   * <p>Five arities, one per shape, because the JVM has no variadic generics and every library that
   * has faced this made the same choice: {@code kotlinx.coroutines} gives {@code Flow.combine}
   * overloads for two through five flows, as do RxJava and Reactor for {@code zip}. Beyond five, or
   * where the parents share a type, use {@link #fold}.
   *
   * <p>The same, for types already declared.
   */
  @Override
  public <I, O> Derivation<I, O> derivation(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      Function<I, O> function,
      Consumer<DerivationConfig> customizer) {
    return constitute(
        name,
        List.<OccludedType<?>>of(input),
        output,
        (values, context) ->
            Optional.ofNullable(function.apply(input.type().rawClass().cast(values.getFirst()))),
        false,
        customizer,
        portals::<I, O>derivation);
  }

  /**
   * The same, for a derivation that may decline: a lookup that finds nothing, a check that fails.
   *
   * <p>The same, for types already declared.
   */
  @Override
  public <I, O> Derivation<I, O> checking(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      BiFunction<I, AccessContext, Optional<O>> function,
      Consumer<DerivationConfig> customizer) {
    return constitute(
        name,
        List.<OccludedType<?>>of(input),
        output,
        (values, context) ->
            function.apply(input.type().rawClass().cast(values.getFirst()), context),
        false,
        customizer,
        portals::<I, O>derivation);
  }

  /**
   * Mints the authority to fold any number of values of one type into a new one.
   *
   * <p>The result carries the join of every parent's label, so folding two tenants' data yields
   * something labelled for both, which no sink admits.
   *
   * <p>The same, for types already declared.
   */
  @Override
  public <I, O> Fold<I, O> fold(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      Function<List<I>, O> function,
      Consumer<DerivationConfig> customizer) {
    return constitute(
        name,
        List.<OccludedType<?>>of(input),
        output,
        (values, context) ->
            Optional.ofNullable(
                function.apply(values.stream().map(v -> input.type().rawClass().cast(v)).toList())),
        true,
        customizer,
        portals::<I, O>fold);
  }

  /**
   * Declares one derivation-shaped authority and hands back the portal.
   *
   * <p>Every arity comes through here, which is why the ceiling check lives in one place. A
   * derivation reads plaintext, so it has to say what it may look at; there is no default, because
   * a default here would be a policy nobody wrote.
   */
  private <O, C> C constitute(
      String name,
      List<OccludedType<?>> inputTypes,
      OccludedType<O> outputType,
      BiFunction<List<Object>, AccessContext, Optional<O>> function,
      boolean fold,
      Consumer<DerivationConfig> customizer,
      Function<DerivationSpec<O>, C> capability) {
    Objects.requireNonNull(name, "a derivation needs a name");
    Objects.requireNonNull(customizer, "a derivation needs to say what it may read");
    DerivationConfig settings = new DerivationConfig();
    customizer.accept(settings);
    if (settings.ceiling() == null) {
      throw new IllegalStateException(
          "'"
              + name
              + "' reads plaintext, so it needs a ceiling: call accepting(...) with what it may"
              + " look at, saying any() on the axes it is deliberately broad about");
    }
    DerivationSpec<O> spec =
        new DerivationSpec<>(
            name,
            inputTypes,
            outputType,
            function,
            settings.ceiling(),
            settings.relabel(),
            settings.availableTo(),
            fold);
    declaring(() -> declarations.derivation(spec));
    return capability.apply(spec);
  }

  /**
   * Mints the authority to ask one question of a value without the value leaving.
   *
   * <p>Boolean, and registered here rather than named at a call site, for the reasons set out on
   * {@link Query}.
   *
   * <p>The same, for a type already declared.
   */
  @Override
  public <I, Q> Query<I, Q> query(
      String name,
      OccludedType<I> input,
      Class<Q> against,
      Query.Asking<I, Q> asking,
      Consumer<QueryConfig> customizer) {
    Objects.requireNonNull(name, "a question needs a name");
    Objects.requireNonNull(customizer, "a question needs to say what it may read");
    QueryConfig settings = new QueryConfig();
    customizer.accept(settings);
    if (settings.ceiling() == null) {
      throw new IllegalStateException(
          "'"
              + name
              + "' reads plaintext to answer, so it needs a ceiling: call accepting(...) with what"
              + " it may look at, saying any() on the axes it is deliberately broad about");
    }
    QuerySpec<I, Q> spec =
        new QuerySpec<>(name, input, asking, settings.ceiling(), settings.availableTo());
    declaring(() -> declarations.query(spec));
    return portals.query(spec);
  }

  /**
   * Mints the authority to forget a value and everything derived from it.
   *
   * <p>The policy sees the label of what is about to be destroyed as well as who is asking, because
   * who alone is not enough: a policy that only asks the caller's role lets one tenant's compliance
   * officer destroy another tenant's records.
   *
   * <pre>{@code
   * Erasure compliance = charter.erasure("compliance", (label, ctx) ->
   *     ctx.has("role", "compliance") && LATTICE.permits(label, everythingIMayRead(ctx)));
   * }</pre>
   *
   * <p>An application that declares none keeps a store nothing can erase from, and one that
   * declares several -- a compliance officer, a retention job -- gets each named in the record.
   */
  @Override
  public Erasure erasure(String name, BiPredicate<Label, AccessContext> mayErase) {
    Objects.requireNonNull(name, "an erasure needs a name");
    Objects.requireNonNull(mayErase, "an erasure needs a policy");
    ErasureSpec spec = new ErasureSpec(name, mayErase);
    declaring(() -> declarations.erasure(spec));
    return portals.erasure(spec);
  }

  /**
   * Mints the authority to read what a value is labelled and where it came from.
   *
   * <p>Checked against this ceiling and recorded like every other access, because a label names a
   * tenant or a project codeword and is protected everywhere else as though it were the value.
   */
  @Override
  public Inspection inspection(String name, Function<AccessContext, Ceiling> ceiling) {
    Objects.requireNonNull(name, "an inspection needs a name");
    Objects.requireNonNull(ceiling, "an inspection needs a ceiling");
    InspectionSpec spec = new InspectionSpec(name, ceiling);
    declaring(() -> declarations.inspection(spec));
    return portals.inspection(spec);
  }

  /** The same, for a ceiling that does not depend on who is asking. */
  @Override
  public Inspection inspection(String name, Ceiling ceiling) {
    Objects.requireNonNull(ceiling, "an inspection needs a ceiling");
    return inspection(name, context -> ceiling);
  }

  // ------------------------------------------------------------------ what a charter reports

  /**
   * What this charter permits, rendered.
   *
   * <p>Administrative, and deliberately not something a portal offers. Reading it tells you what
   * the system can do; it is not a way to do any of it.
   */
  @Override
  public Manifest manifest() {
    return manifest(AccessContext.empty());
  }

  @Override
  public Manifest manifest(AccessContext as) {
    Objects.requireNonNull(as, "a manifest is rendered for some access, even an empty one");
    synchronized (declarations) {
      return Manifests.of(declarations, as);
    }
  }
}
