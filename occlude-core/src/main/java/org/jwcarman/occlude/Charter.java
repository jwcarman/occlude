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

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;

/**
 * What an application declares its authority with.
 *
 * <p>A charter constitutes the portals an application holds: the questions it asks about every
 * value, the doors values may reach, the transformations that are legal, and what each of them is
 * entitled to see. Declaring one hands back the portal, and holding that portal is the only way to
 * perform the operation -- there is no registry and no lookup by name.
 *
 * <p><b>Two things are deliberately absent from this interface.</b>
 *
 * <p>{@code seal}, which brings a charter into force, is not here. Whoever holds the thing that can
 * seal decides when an application's authority graph stops growing, and that is not a decision a
 * bean should be able to make by naming a type in its constructor. The implementation carries it,
 * and whatever constructs a charter keeps that reference; everything else is handed this.
 *
 * <p>{@code erase}, which forgets a value and everything derived from it, is not here either.
 * Nothing in an application has needed it, and leaving it off means the answer to "which code can
 * destroy customer data" is <i>none, structurally</i>. If that changes, erasure earns a portal like
 * every other operation, rather than a method on the object everybody is handed.
 *
 * <p>Nor is anything that reports on a <i>value</i>. {@code label} said how a value was labelled,
 * {@code lineage} said what it was made from, and {@code holds} said whether it was here at all --
 * none of them moved a value anywhere, which is why they looked harmless. But each answered a
 * question about real data with no ceiling, no {@code availableTo}, and no line in the record, and
 * each took a {@code String}, so any identifier could be asked about. A label names a tenant or a
 * project codeword; storage encrypts it and the audit protects it exactly like a value. Handing it
 * out from the object every bean is given said the opposite.
 *
 * <p>What is left is declaring, and reporting on the <i>declarations</i> rather than on anything
 * held. That distinction is the whole of it: a manifest says what this application can do, which is
 * safe to publish because it describes the system and never a value. Whoever constructs a charter
 * keeps the rest, the same way it keeps {@code seal} and {@code erase}.
 */
public interface Charter {

  /** The questions this charter asks about every value it holds. */
  Axes axes();

  /** Whether this charter has been brought into force. */
  boolean sealed();

  // ------------------------------------------------------------------ constituting authority

  /** A door values enter through, labelled the same way every time. */
  <T> Occlude<T> source(String name, OccludedType<T> type, Label label);

  /** The same, for a label that depends on who is acting. */
  <T> Occlude<T> source(
      String name, OccludedType<T> type, Function<AccessContext, Label> labelling);

  /** The same, for a label that also depends on what is arriving. */
  <T> Occlude<T> source(
      String name, OccludedType<T> type, BiFunction<T, AccessContext, Label> labelling);

  /** Somewhere values may go, and the types it is allowed to read. */
  Sink sink(String name, Function<AccessContext, Ceiling> ceiling, OccludedType<?>... reads);

  /** The same, for a ceiling that does not depend on who is asking. */
  Sink sink(String name, Ceiling ceiling, OccludedType<?>... reads);

  /** A sink declared elsewhere. */
  Charter sink(SinkSpec sink);

  /**
   * The authority to make one value from another.
   *
   * <p>The customizer says what the derivation may read, and whether it weakens a label. Both are
   * settled here and cannot change afterwards, which is what makes the manifest a complete answer.
   */
  <I, O> Derivation<I, O> derivation(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      Function<I, O> function,
      Consumer<DerivationConfig> customizer);

  /**
   * The same, for a derivation that may decline: a lookup that finds nothing, a check that fails.
   */
  <I, O> Derivation<I, O> checking(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      BiFunction<I, AccessContext, Optional<O>> function,
      Consumer<DerivationConfig> customizer);

  /** The authority to make one value from many of one type. */
  <I, O> Fold<I, O> fold(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      Function<List<I>, O> function,
      Consumer<DerivationConfig> customizer);

  /** The authority to ask one question of a value without the value leaving. */
  <I, Q> Query<I, Q> query(
      String name,
      OccludedType<I> input,
      Class<Q> against,
      Query.Asking<I, Q> asking,
      Consumer<QueryConfig> customizer);

  // ------------------------------------------------------------------ settling how it behaves

  /** Where the access happening right now comes from. */
  Charter currentAccess(AccessContextProvider currentAccess);

  /** Who may forget a value, which is the one decision a label cannot make on its own. */
  Charter mayErase(BiPredicate<Label, AccessContext> mayErase);

  // ------------------------------------------------------------------ what it reports

  /**
   * What this charter permits, rendered.
   *
   * <p>Answerable before it has been sealed to anything, because it is a statement about the
   * declarations rather than about any value: a build can render it, diff it against the last
   * release, and fail on a change nobody meant to make.
   */
  Manifest manifest();

  /**
   * The same, rendered for one access.
   *
   * <p>A door whose ceiling reads the tenant out of the access cannot say what it accepts without
   * one, and in a multi-tenant application that is every door. Rendered for nobody, such a manifest
   * reports that it could not evaluate a single ceiling -- useless in exactly the case it is for.
   * There is no such thing as what a door accepts in general, so a manifest names the access it was
   * rendered for and a build renders one per representative caller.
   */
  Manifest manifest(AccessContext as);
}
