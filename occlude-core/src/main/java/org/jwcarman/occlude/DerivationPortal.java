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
import org.jwcarman.occlude.storage.AuditRecord;

/** The authority to make one value from one other, as it was declared. */
final class DerivationPortal<I, O> implements Derivation<I, O> {

  private final DerivationSpec<O> spec;
  private final Operations operations;

  DerivationPortal(DerivationSpec<O> spec, Operations operations) {
    this.spec = spec;
    this.operations = operations;
  }

  @Override
  public Derived<O> derive(Occluded<I> parent) {
    return operations
        .observing()
        .observe(
            AuditRecord.Operation.DERIVE,
            spec.name(),
            () -> operations.deriving().derive(spec, List.of(parent)),
            Observing::derived);
  }

  @Override
  public String toString() {
    return "derivation '" + spec.name() + "'";
  }
}
