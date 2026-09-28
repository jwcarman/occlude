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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;

/**
 * How a store is built: the axes its labels are said on, and the sinks values may reach.
 *
 * <p>Both are wiring-time decisions on purpose. An axis supplied later could reorder what is
 * permitted underneath values already stored, and a sink supplied at a call site would let any code
 * invent its own permission.
 */
public final class DefaultCharter implements Charter {

  private final Axes axes;

  // Written only while this charter is being configured, which is single-threaded by contract: an
  // application wires itself on one thread, and nothing it constitutes can act until it is sealed.
  // Sealing copies all of it into an immutable snapshot and publishes that with one atomic write,
  // which is the only moment any of it crosses to the threads that will use a portal.
  private final Map<String, OccludedType<?>> types = new LinkedHashMap<>();
  private final Map<String, OccludedType<?>> sources = new LinkedHashMap<>();

  /**
   * What each door hands out, which the charter did not used to know.
   *
   * <p>The readable types lived only in the object handed back to whoever declared the door, so
   * nothing could report what a sink produces or work out whether anything produces what it reads.
   * A door nobody can reach is dead authority, and it is also the shape a half-applied rename
   * takes.
   */
  private final Map<String, Set<String>> sinkReads = new LinkedHashMap<>();

  private final List<SinkSpec> sinks = new ArrayList<>();
  private final List<DerivationSpec<?>> derivations = new ArrayList<>();
  private final List<QuerySpec<?, ?>> queries = new ArrayList<>();
  private AccessContextProvider currentAccess = AccessContextProvider.none();
  private BiPredicate<Label, AccessContext> mayErase = (label, context) -> false;
  private final Lifecycle lifecycle = new Lifecycle();
  private final Portals portals = new Portals(lifecycle);

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
  @SafeVarargs
  public DefaultCharter(Axis<?>... axes) {
    this(Axes.of(axes));
  }

  /** The schema this charter was constituted with. */
  public Axes axes() {
    return axes;
  }

  /**
   * Brings every portal this charter constituted into force at once, against this storage.
   *
   * <p>Irreversible. After it returns, nothing further may be declared and everything already
   * declared works. There is no way back: no unseal, no rebind, no replacing the storage.
   */
  public void seal(Storage storage) {
    Objects.requireNonNull(storage, "a charter is sealed to a storage");
    lifecycle.seal(axes, this::declared, storage);
  }

  /** Whether this charter has been brought into force. */
  public boolean sealed() {
    return lifecycle.sealed();
  }

  Lifecycle lifecycle() {
    return lifecycle;
  }

  /** What has been declared, from wherever it currently lives. */
  private Configuration configuration() {
    return lifecycle.configuration(this::declared);
  }

  /** The fields as they stand, copied, which is what sealing publishes. */
  private Configuration declared() {
    return new Configuration(
        types, sources, sinkReads, sinks, derivations, queries, currentAccess, mayErase);
  }

  /** Somewhere values may go. Registered once; referenced by name forever after. */
  @Override
  public DefaultCharter sink(SinkSpec sink) {
    Objects.requireNonNull(sink, "a sink must not be null");
    lifecycle.stillWriting();
    sinks.add(sink);
    return this;
  }

  // ------------------------------------------------------------------ the types it will keep

  /**
   * Records a type and refuses a name that already means something else.
   *
   * <p>Called by every declaration, so the check does not depend on how the type was declared. A
   * name has to identify one type: two of them sharing a name means a reader is handed the wrong
   * one, and finding that out at startup beats finding it out from a decode failure in production.
   */
  <T> OccludedType<T> registered(OccludedType<T> declared) {
    OccludedType<?> existing = types.get(declared.name());
    if (existing != null && !existing.type().getType().equals(declared.type().getType())) {
      throw new IllegalStateException(
          ("two types both want the name '%s': %s and %s. A stored name has to identify one type,"
                  + " or a reader gets handed the wrong one. Name one of them explicitly.")
              .formatted(
                  declared.name(),
                  existing.type().getType().getTypeName(),
                  declared.type().getType().getTypeName()));
    }
    return declared;
  }

  /** Everything this charter was told it may keep, for the manifest. */
  Collection<OccludedType<?>> types() {
    return List.copyOf(configuration().types().values());
  }

  /** Validates each type and folds it into the configuration the caller is about to leave. */
  void recording(OccludedType<?>... declared) {
    for (OccludedType<?> type : declared) {
      registered(type);
      types.put(type.name(), type);
    }
  }

  // ------------------------------------------------------------------ declaring capabilities

  /** A source whose label depends on neither what arrives nor who is acting. */
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
  public <T> Occlude<T> source(
      String name, OccludedType<T> type, Function<AccessContext, Label> labelling) {
    return source(name, type, (value, context) -> labelling.apply(context));
  }

  public <T> Occlude<T> source(
      String name, OccludedType<T> type, BiFunction<T, AccessContext, Label> labelling) {
    Objects.requireNonNull(name, "a source needs a name");
    Objects.requireNonNull(type, "a source needs to know what it accepts");
    Objects.requireNonNull(labelling, "a source needs to say how it labels what arrives");
    lifecycle.stillWriting();
    if (sources.putIfAbsent(name, type) != null) {
      throw new IllegalStateException("two sources are registered as '" + name + "'");
    }
    recording(type);
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
  @SafeVarargs
  public final Sink sink(
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
    Set<String> names = new LinkedHashSet<>();
    for (OccludedType<?> type : reads) {
      names.add(type.name());
    }
    lifecycle.stillWriting();
    recording(reads);
    sinks.add(Sinks.varying(name, ceiling));
    sinkReads.put(name, Collections.unmodifiableSet(names));
    return portals.sink(name, Collections.unmodifiableSet(names));
  }

  /** The same, for a ceiling that does not depend on who is asking. */
  @SafeVarargs
  public final Sink sink(String name, Ceiling ceiling, OccludedType<?>... reads) {
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
    lifecycle.stillWriting();
    OccludedType<?>[] declared = new OccludedType<?>[inputTypes.size() + 1];
    inputTypes.toArray(declared);
    declared[inputTypes.size()] = outputType;
    recording(declared);
    derivations.add(spec);
    return capability.apply(spec);
  }

  List<DerivationSpec<?>> derivations() {
    return configuration().derivations();
  }

  /**
   * Where the access happening right now comes from.
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
   * .currentAccess(() -> AccessContext.of(Map.of(
   *     "tenant", CurrentTenant.get(),
   *     "principal", SecurityContextHolder.getContext().getAuthentication().getName())))
   * }</pre>
   *
   * <p>An application with no notion of identity says nothing and every context is empty.
   */
  @Override
  public DefaultCharter currentAccess(AccessContextProvider currentAccess) {
    Objects.requireNonNull(currentAccess, "an access source must not be null");
    lifecycle.stillWriting();
    this.currentAccess = currentAccess;
    return this;
  }

  AccessContextProvider currentAccess() {
    return currentAccess;
  }

  /**
   * Who may erase what.
   *
   * <p>Takes the label of the value being erased as well as who is asking, because who alone is not
   * enough: a policy that only asks the caller's role lets one tenant's compliance officer destroy
   * another tenant's records. Whatever the rule, it has to see what is about to be destroyed.
   *
   * <pre>{@code
   * .mayErase((label, ctx) ->
   *     ctx.has("role", "compliance") && LATTICE.permits(label, everythingIMayRead(ctx)))
   * }</pre>
   *
   * <p>Refuses everyone until this says otherwise, because erasure is the one operation a label
   * does not govern on its own. Every other gate asks whether a value may be <i>disclosed</i>
   * somewhere; a label has nothing to say about whether it may be <i>destroyed</i>, and "possession
   * is not authority" is a rule about reading. An application that never erases says nothing and
   * keeps a store that cannot.
   *
   * <p><b>Descendants go regardless.</b> The check is against the root, and everything derived from
   * it is removed whether or not it is labelled more constrained -- which is what erasure means. A
   * value derived from two customers dies with either of them.
   */
  @Override
  public DefaultCharter mayErase(BiPredicate<Label, AccessContext> mayErase) {
    Objects.requireNonNull(mayErase, "an erasure policy must not be null");
    lifecycle.stillWriting();
    this.mayErase = mayErase;
    return this;
  }

  BiPredicate<Label, AccessContext> mayErase() {
    return mayErase;
  }

  List<SinkSpec> sinks() {
    return configuration().sinks();
  }

  Set<String> sources() {
    return configuration().sources().keySet();
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
    lifecycle.stillWriting();
    recording(input);
    queries.add(spec);
    return portals.query(spec);
  }

  /** What a query still needs said about it before it becomes a capability. */
  List<QuerySpec<?, ?>> queries() {
    return configuration().queries();
  }

  // ------------------------------------------------------------------ what a charter reports

  /**
   * What this charter permits, rendered.
   *
   * <p>Administrative, and deliberately not something a portal offers. Reading it tells you what
   * the system can do; it is not a way to do any of it.
   */
  public Manifest manifest() {
    return manifest(AccessContext.empty());
  }

  @Override
  public Manifest manifest(AccessContext as) {
    Objects.requireNonNull(as, "a manifest is rendered for some access, even an empty one");
    return Manifests.of(configuration(), as);
  }

  /**
   * How a value is labelled. For a report or an operator, never for a decision.
   *
   * <p>Deliberately not on {@link Charter}. A label names a tenant or a project codeword, which is
   * why storage encrypts it and the audit protects it like a value; handing it out from the object
   * every bean holds, with no ceiling and no line in the record, would say it is ordinary. Whoever
   * constructs a charter keeps this, the same way it keeps {@code seal} and {@code erase}.
   */
  public Label label(Occluded<?> occluded) {
    return label(occluded.id());
  }

  /** The same, for an identifier that arrived without its type. */
  public Label label(String id) {
    return lifecycle.operations().gate().label(id);
  }

  /** Where a value came from. */
  public Lineage lineage(Occluded<?> occluded) {
    return lineage(occluded.id());
  }

  /** The same, for an identifier that arrived without its type. */
  public Lineage lineage(String id) {
    return lifecycle.operations().gate().lineage(id);
  }

  /** Whether this charter is holding a value at all. */
  public boolean holds(Occluded<?> occluded) {
    return holds(occluded.id());
  }

  /** The same, for an identifier that arrived without its type. */
  public boolean holds(String id) {
    return lifecycle.operations().gate().holds(id);
  }

  /**
   * Forgets a value and everything derived from it.
   *
   * <p>Still guarded by {@code mayErase} against the acting context rather than by holding a
   * portal, which makes it the last authority here that is checked rather than held.
   */
  public int erase(Occluded<?> root) {
    return lifecycle.operations().erasing().erase(root);
  }
}
