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
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import org.jwcarman.occlude.lattice.Label;

/**
 * The portals a charter hands back, each closed over the lifecycle it acts through.
 *
 * <p>Minting is all this does. What a portal may do was settled when it was declared, and whether
 * it may do it now is the lifecycle's answer; the checks themselves belong to the operations.
 */
final class Portals {

  private final Lifecycle lifecycle;

  Portals(Lifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  <T> Occlude<T> source(
      String name, OccludedType<T> type, BiFunction<T, AccessContext, Label> labelling) {
    String what = "source '" + name + "'";
    return new Occlude<T>() {
      @Override
      public Occluded<T> occlude(T value) {
        return lifecycle.operations().occluding().occlude(name, type, labelling, value);
      }

      @Override
      public String toString() {
        return what;
      }
    };
  }

  Sink sink(String name, Set<String> reads) {
    return new Door(name, reads, lifecycle);
  }

  <I, O> Derivation<I, O> derivation(DerivationSpec<O> spec) {
    return parent -> lifecycle.operations().deriving().derive(spec, List.of(parent));
  }

  <I, O> Fold<I, O> fold(DerivationSpec<O> spec) {
    return parents -> lifecycle.operations().deriving().derive(spec, List.copyOf(parents));
  }

  <I, Q> Query<I, Q> query(QuerySpec<I, Q> spec) {
    String what = "query '" + spec.name() + "'";
    return new Query<I, Q>() {
      @Override
      public Answer ask(Occluded<I> about, Q against) {
        return lifecycle.operations().querying().ask(spec, about, against);
      }

      @Override
      public String toString() {
        return what;
      }
    };
  }

  /** The implementation of a sink: a name, what it reads, and what it is attached to. */
  private record Door(String name, Set<String> reads, Lifecycle lifecycle) implements Sink {

    @Override
    public <T> Reveal<T> reading(OccludedType<T> type) {
      Objects.requireNonNull(type, "a reader needs to say what comes out of it");
      if (!reads.contains(type.name())) {
        throw new IllegalStateException(
            ("'%s' does not read %s. It was declared to read %s, and a reader cannot add to that"
                    + " list.")
                .formatted(name, type.name(), reads));
      }
      String door = name;
      String what = "'" + door + "' reading " + type.name();
      return new Reveal<>() {
        @Override
        public OccludedType<T> type() {
          return type;
        }

        @Override
        public Revealed<T> reveal(Occluded<T> occluded) {
          return lifecycle.operations().revealing().reveal(occluded, type, door);
        }

        @Override
        public String toString() {
          return what;
        }
      };
    }
  }
}
