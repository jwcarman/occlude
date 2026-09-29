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

import java.util.Set;
import java.util.function.BiFunction;
import org.jwcarman.occlude.lattice.Label;

/**
 * Mints every portal a charter hands out, each holding its own declaration and the operations it
 * will reach once the charter is bound.
 *
 * <p>Its own type so the charter depends on one factory rather than on every portal class, and
 * every method returns the public interface: nothing outside this package can tell a portal's class
 * or reach what it holds.
 */
final class Portals {

  private final Operations operations;

  Portals(Operations operations) {
    this.operations = operations;
  }

  <T> Occlude<T> source(
      String name, OccludedType<T> type, BiFunction<T, AccessContext, Label> labelling) {
    return new SourcePortal<>(name, type, labelling, operations);
  }

  Sink sink(SinkSpec sink, Set<String> reads) {
    return new SinkPortal(sink, reads, operations);
  }

  <I, O> Derivation<I, O> derivation(DerivationSpec<O> spec) {
    return new DerivationPortal<>(spec, operations);
  }

  <I, O> Fold<I, O> fold(DerivationSpec<O> spec) {
    return new FoldPortal<>(spec, operations);
  }

  <I, Q> Query<I, Q> query(QuerySpec<I, Q> spec) {
    return new QueryPortal<>(spec, operations);
  }

  Erasure erasure(ErasureSpec spec) {
    return new ErasurePortal(spec, operations);
  }

  Inspection inspection(InspectionSpec spec) {
    return new InspectionPortal(spec, operations);
  }
}
