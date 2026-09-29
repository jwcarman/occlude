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

import java.util.Objects;
import java.util.Set;

/**
 * Somewhere values may go: the whole sink, ceiling included, and the types it reads.
 *
 * <p>A reader minted from this carries the same sink, so a reveal is decided against the ceiling
 * the portal holds rather than one looked up by name.
 */
final class SinkPortal implements Sink {

  private final SinkSpec sink;
  private final Set<String> reads;
  private final Operations operations;

  SinkPortal(SinkSpec sink, Set<String> reads, Operations operations) {
    this.sink = sink;
    this.reads = reads;
    this.operations = operations;
  }

  @Override
  public <T> Reveal<T> reading(OccludedType<T> type) {
    Objects.requireNonNull(type, "a reader needs to say what comes out of it");
    if (!reads.contains(type.name())) {
      throw new IllegalStateException(
          ("'%s' does not read %s. It was declared to read %s, and a reader cannot add to that"
                  + " list.")
              .formatted(sink.name(), type.name(), reads));
    }
    return new RevealPortal<>(sink, type, operations);
  }

  @Override
  public String toString() {
    return "sink '" + sink.name() + "'";
  }
}
