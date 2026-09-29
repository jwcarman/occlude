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
import java.util.Set;

/**
 * What a charter has been told, in the order it was told, for reporting and for refusing a
 * declaration that clashes with an earlier one.
 *
 * <p>Nothing at runtime reads this: every portal carries its own declaration. It exists because a
 * manifest has to say what the whole charter permits, and because two doors under one name or two
 * types under one stored name are mistakes worth refusing at startup.
 *
 * <p>Not thread-safe on its own. {@link DefaultCharter} reads and writes it only under one lock,
 * the same one binding takes, so a manifest rendered on a request thread sees everything declared
 * and a declaration cannot slip in after binding.
 */
final class Declarations {

  private final Map<String, OccludedType<?>> types = new LinkedHashMap<>();
  private final Map<String, OccludedType<?>> sources = new LinkedHashMap<>();

  /**
   * What each door hands out.
   *
   * <p>The readable types live in the portal handed back to whoever declared the door, so without
   * this nothing could report what a sink produces or work out whether anything produces what it
   * reads. A door nobody can reach is dead authority, and it is also the shape a half-applied
   * rename takes.
   */
  private final Map<String, Set<String>> sinkReads = new LinkedHashMap<>();

  private final Map<String, SinkSpec> sinks = new LinkedHashMap<>();
  private final Map<String, DerivationSpec<?>> derivations = new LinkedHashMap<>();
  private final Map<String, QuerySpec<?, ?>> queries = new LinkedHashMap<>();
  private final Map<String, ErasureSpec> erasures = new LinkedHashMap<>();
  private final Map<String, InspectionSpec> inspections = new LinkedHashMap<>();

  // ------------------------------------------------------------------ recording

  void source(String name, OccludedType<?> type) {
    unique(sources, name, type, "sources");
    recording(type);
  }

  void sink(SinkSpec sink, Set<String> reads, OccludedType<?>... types) {
    unique(sinks, sink.name(), sink, "sinks");
    recording(types);
    sinkReads.put(sink.name(), Collections.unmodifiableSet(reads));
  }

  void derivation(DerivationSpec<?> derivation) {
    unique(derivations, derivation.name(), derivation, "derivations");
    recording(derivation.inputTypes().toArray(OccludedType<?>[]::new));
    recording(derivation.outputType());
  }

  void query(QuerySpec<?, ?> query) {
    unique(queries, query.name(), query, "queries");
    recording(query.inputType());
  }

  void erasure(ErasureSpec erasure) {
    unique(erasures, erasure.name(), erasure, "erasures");
  }

  void inspection(InspectionSpec inspection) {
    unique(inspections, inspection.name(), inspection, "inspections");
  }

  private static <V> void unique(Map<String, V> kind, String name, V declared, String what) {
    if (kind.containsKey(name)) {
      throw new IllegalStateException("two " + what + " are registered as '" + name + "'");
    }
    kind.put(name, declared);
  }

  /**
   * Records each type and refuses a name that already means something else.
   *
   * <p>A name has to identify one type: two sharing a name means a reader is handed the wrong one,
   * and finding that out at startup beats finding it out from a decode failure in production.
   */
  private void recording(OccludedType<?>... declared) {
    for (OccludedType<?> type : declared) {
      OccludedType<?> existing = types.get(type.name());
      if (existing != null && !existing.type().getType().equals(type.type().getType())) {
        throw new IllegalStateException(
            ("two types both want the name '%s': %s and %s. A stored name has to identify one"
                    + " type, or a reader gets handed the wrong one. Name one of them explicitly.")
                .formatted(
                    type.name(),
                    existing.type().getType().getTypeName(),
                    type.type().getType().getTypeName()));
      }
      types.put(type.name(), type);
    }
  }

  // ------------------------------------------------------------------ reading, for the manifest

  Map<String, OccludedType<?>> sources() {
    return Collections.unmodifiableMap(sources);
  }

  Map<String, Set<String>> sinkReads() {
    return Collections.unmodifiableMap(sinkReads);
  }

  List<SinkSpec> sinks() {
    return List.copyOf(sinks.values());
  }

  List<DerivationSpec<?>> derivations() {
    return List.copyOf(derivations.values());
  }

  List<QuerySpec<?, ?>> queries() {
    return List.copyOf(queries.values());
  }

  List<ErasureSpec> erasures() {
    return List.copyOf(erasures.values());
  }

  List<InspectionSpec> inspections() {
    return List.copyOf(inspections.values());
  }
}
