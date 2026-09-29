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

/** The authority to ask one question of a value without the value leaving. */
final class QueryPortal<I, Q> implements Query<I, Q> {

  private final QuerySpec<I, Q> spec;
  private final Operations operations;

  QueryPortal(QuerySpec<I, Q> spec, Operations operations) {
    this.spec = spec;
    this.operations = operations;
  }

  @Override
  public Answer ask(Occluded<I> about, Q against) {
    return operations
        .observing()
        .observe(
            AuditRecord.Operation.QUERY,
            spec.name(),
            () -> operations.querying().ask(spec, about, against),
            Observing::answered);
  }

  @Override
  public String toString() {
    return "query '" + spec.name() + "'";
  }
}
