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

import org.jwcarman.occlude.storage.AuditRecord;

/** One type coming out of one sink. A typed view of the sink rather than a grant of its own. */
final class RevealPortal<T> implements Reveal<T> {

  private final SinkSpec sink;
  private final OccludedType<T> type;
  private final Operations operations;

  RevealPortal(SinkSpec sink, OccludedType<T> type, Operations operations) {
    this.sink = sink;
    this.type = type;
    this.operations = operations;
  }

  @Override
  public OccludedType<T> type() {
    return type;
  }

  @Override
  public Revealed<T> reveal(Occluded<T> occluded) {
    return operations
        .observing()
        .observe(
            AuditRecord.Operation.REVEAL,
            sink.name(),
            () -> operations.revealing().reveal(occluded, type, sink),
            Observing::revealed);
  }

  @Override
  public String toString() {
    return "'" + sink.name() + "' reading " + type.name();
  }
}
