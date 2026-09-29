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

/** The authority to forget a value and everything made from it, under one declared policy. */
final class ErasurePortal implements Erasure {

  private final ErasureSpec spec;
  private final Operations operations;

  ErasurePortal(ErasureSpec spec, Operations operations) {
    this.spec = spec;
    this.operations = operations;
  }

  @Override
  public Erased erase(Occluded<?> root) {
    return operations
        .observing()
        .observe(
            AuditRecord.Operation.ERASE,
            spec.name(),
            () -> operations.erasing().erase(root, spec),
            Observing::erased);
  }

  @Override
  public String toString() {
    return "erasure '" + spec.name() + "'";
  }
}
