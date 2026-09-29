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

import java.util.function.BiFunction;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.AuditRecord;

/** A door values enter through: its name, what it accepts, and how it labels what arrives. */
final class SourcePortal<T> implements Occlude<T> {

  private final String name;
  private final OccludedType<T> type;
  private final BiFunction<T, AccessContext, Label> labelling;
  private final Operations operations;

  SourcePortal(
      String name,
      OccludedType<T> type,
      BiFunction<T, AccessContext, Label> labelling,
      Operations operations) {
    this.name = name;
    this.type = type;
    this.labelling = labelling;
    this.operations = operations;
  }

  @Override
  public Occluded<T> occlude(T value) {
    return operations
        .observing()
        .observe(
            AuditRecord.Operation.CONCEAL,
            name,
            () -> operations.occluding().occlude(name, type, labelling, value),
            Observing::returned);
  }

  @Override
  public String toString() {
    return "source '" + name + "'";
  }
}
