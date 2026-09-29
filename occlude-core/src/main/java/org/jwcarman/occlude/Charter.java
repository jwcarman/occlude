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
import org.jwcarman.occlude.manifest.Manifest;

/**
 * What an application declares its authority with.
 *
 * <p>A charter constitutes the portals an application holds: the questions it asks about every
 * value, the doors values may reach, the transformations that are legal, and what each of them is
 * entitled to see. Declaring one hands back the portal, and holding that portal is the only way to
 * perform the operation -- there is no registry and no lookup by name.
 *
 * <p>Every portal carries everything its operation needs, and binding the charter to storage brings
 * them all into force at once. After that the charter has no further part to play.
 *
 * <p>{@code bind} is deliberately absent from this interface. Whoever holds the thing that can bind
 * decides when an application's authority graph stops growing, and that is not a decision a bean
 * should be able to make by naming a type in its constructor. The implementation carries it, and
 * whatever constructs a charter keeps that reference; everything else is handed this.
 *
 * <p>Erasing a value and reading its label are portals like every other operation. Each used to be
 * a method on the object that constructed a charter, answered with no line in the record or checked
 * against a policy nobody held; as portals, the answer to "which code can destroy customer data" or
 * "which code can learn a tenant's classification" is whoever was handed one, and the manifest
 * names them.
 *
 * <p>What is reported here is the <i>declarations</i>, never anything held: a manifest says what
 * this application can do, which is safe to publish because it describes the system and never a
 * value.
 */
public interface Charter {

  /**
   * The questions this charter asks about every value it holds.
   *
   * @return the axes, in the order they are read
   */
  Axes axes();

  // ------------------------------------------------------------------ constituting authority

  /**
   * A door values enter through, labelled the same way every time.
   *
   * @param <T> what the door admits
   * @param name the name the manifest and the record know it by, never blank
   * @param type the type of value that enters
   * @param label the label every value entering is given
   * @return the door
   */
  <T> Occlude<T> source(String name, OccludedType<T> type, Label label);

  /**
   * The same, for a label that depends on who is acting.
   *
   * @param <T> what the door admits
   * @param name the name the manifest and the record know it by, never blank
   * @param type the type of value that enters
   * @param labelling chooses the label from who is acting
   * @return the door
   */
  <T> Occlude<T> source(
      String name, OccludedType<T> type, Function<AccessContext, Label> labelling);

  /**
   * The same, for a label that also depends on what is arriving.
   *
   * @param <T> what the door admits
   * @param name the name the manifest and the record know it by, never blank
   * @param type the type of value that enters
   * @param labelling chooses the label from what is arriving and who is acting
   * @return the door
   */
  <T> Occlude<T> source(
      String name, OccludedType<T> type, BiFunction<T, AccessContext, Label> labelling);

  /**
   * Somewhere values may go, and the types it is allowed to read.
   *
   * @param name the name the manifest and the record know it by, never blank
   * @param ceiling chooses what the sink may see from who is asking
   * @param reads the types the sink is allowed to read
   * @return the sink
   */
  Sink sink(String name, Function<AccessContext, Ceiling> ceiling, OccludedType<?>... reads);

  /**
   * The same, for a ceiling that does not depend on who is asking.
   *
   * @param name the name the manifest and the record know it by, never blank
   * @param ceiling what the sink may see, whoever is asking
   * @param reads the types the sink is allowed to read
   * @return the sink
   */
  Sink sink(String name, Ceiling ceiling, OccludedType<?>... reads);

  /**
   * A door that reads one type, and the portal that reads it through that door, in one step.
   *
   * <p>The common case. {@code reveal(name, ceiling, MAIL)} is {@code sink(name, ceiling,
   * MAIL).reading(MAIL)}; a door that reads several types is still declared with {@link #sink}.
   *
   * @param <T> what the door reads
   * @param name the name the manifest and the record know it by, never blank
   * @param ceiling chooses what the door may see from who is asking
   * @param type the type the door reads and the portal reveals
   * @return the portal that reads that type through the door
   */
  <T> Reveal<T> reveal(String name, Function<AccessContext, Ceiling> ceiling, OccludedType<T> type);

  /**
   * The same, for a ceiling that does not depend on who is asking.
   *
   * @param <T> what the door reads
   * @param name the name the manifest and the record know it by, never blank
   * @param ceiling what the door may see, whoever is asking
   * @param type the type the door reads and the portal reveals
   * @return the portal that reads that type through the door
   */
  <T> Reveal<T> reveal(String name, Ceiling ceiling, OccludedType<T> type);

  /**
   * The authority to make one value from another.
   *
   * <p>The customizer says what the derivation may read, and whether it weakens a label. Both are
   * settled here and cannot change afterwards, which is what makes the manifest a complete answer.
   *
   * @param <I> what the derivation reads
   * @param <O> what the derivation makes
   * @param name the name the manifest and the record know it by, never blank
   * @param input the type read
   * @param output the type made
   * @param function makes the output from the input
   * @param customizer says what the derivation may read and whether it weakens a label
   * @return the derivation
   */
  <I, O> Derivation<I, O> derivation(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      Function<I, O> function,
      Consumer<DerivationConfig> customizer);

  /**
   * The same, for a derivation that may decline: a lookup that finds nothing, a check that fails.
   *
   * @param <I> what the derivation reads
   * @param <O> what the derivation makes
   * @param name the name the manifest and the record know it by, never blank
   * @param input the type read
   * @param output the type made
   * @param function makes the output from the input and who is asking, or nothing to decline
   * @param customizer says what the derivation may read and whether it weakens a label
   * @return the derivation
   */
  <I, O> Derivation<I, O> checking(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      BiFunction<I, AccessContext, Optional<O>> function,
      Consumer<DerivationConfig> customizer);

  /**
   * The authority to make one value from many of one type.
   *
   * @param <I> what the fold reads
   * @param <O> what the fold makes
   * @param name the name the manifest and the record know it by, never blank
   * @param input the type of each value folded
   * @param output the type made
   * @param function makes one output from all the inputs
   * @param customizer says what the fold may read and whether it weakens a label
   * @return the fold
   */
  <I, O> Fold<I, O> fold(
      String name,
      OccludedType<I> input,
      OccludedType<O> output,
      Function<List<I>, O> function,
      Consumer<DerivationConfig> customizer);

  /**
   * The authority to ask one question of a value without the value leaving.
   *
   * @param <I> what the question is asked about
   * @param <Q> what the question is asked against: a plain value
   * @param name the name the manifest and the record know it by, never blank
   * @param input the type asked about
   * @param against the type of what it is asked against
   * @param asking the question itself, answering with one bit
   * @param customizer says what the question may read
   * @return the query
   */
  <I, Q> Query<I, Q> query(
      String name,
      OccludedType<I> input,
      Class<Q> against,
      Query.Asking<I, Q> asking,
      Consumer<QueryConfig> customizer);

  /**
   * The authority to forget a value and everything derived from it.
   *
   * <p>The policy is asked about the root, with its label and who is asking; descendants go with
   * it.
   *
   * @param name the name the manifest and the record know it by, never blank
   * @param mayErase asked about the root with its label and who is asking; true to allow the
   *     erasure
   * @return the erasure
   */
  Erasure erasure(String name, BiPredicate<Label, AccessContext> mayErase);

  /**
   * The authority to read a value's label and lineage, up to what this ceiling admits.
   *
   * @param name the name the manifest and the record know it by, never blank
   * @param ceiling chooses what the inspection may read from who is asking
   * @return the inspection
   */
  Inspection inspection(String name, Function<AccessContext, Ceiling> ceiling);

  /**
   * The same, for a ceiling that does not depend on who is asking.
   *
   * @param name the name the manifest and the record know it by, never blank
   * @param ceiling what the inspection may read, whoever is asking
   * @return the inspection
   */
  Inspection inspection(String name, Ceiling ceiling);

  // ------------------------------------------------------------------ what it reports

  /**
   * What this charter permits, rendered.
   *
   * <p>Answerable before it has been bound to anything, because it is a statement about the
   * declarations rather than about any value: a build can render it, diff it against the last
   * release, and fail on a change nobody meant to make.
   *
   * @return what this charter permits, for nobody in particular
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
   *
   * @param as the access the manifest is rendered for
   * @return what this charter permits that access
   */
  Manifest manifest(AccessContext as);
}
