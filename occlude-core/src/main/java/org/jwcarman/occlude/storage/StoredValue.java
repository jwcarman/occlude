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
package org.jwcarman.occlude.storage;

import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.lattice.Label;

/**
 * A value on its way into storage.
 *
 * @param value the value itself, which never leaves storage except through the gate
 * @param type what it is, as the caller declared rather than as its class happens to be
 * @param label how it is labelled -- the store is the only authority on this
 * @param lineage where it came from
 */
public record StoredValue(Object value, OccludedType<?> type, Label label, Lineage lineage) {

  /**
   * Says nothing about the value.
   *
   * <p>A record's generated {@code toString} would print it, and this object passes through every
   * storage implementation, where it is exactly the sort of thing that ends up in a log line.
   */
  @Override
  public String toString() {
    return "StoredValue[type=%s, value=<held>]".formatted(type.name());
  }
}
