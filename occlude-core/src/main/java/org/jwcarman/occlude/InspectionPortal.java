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

/** The authority to read a value's label and lineage, up to one declared ceiling. */
final class InspectionPortal implements Inspection {

  private final InspectionSpec spec;
  private final Operations operations;

  InspectionPortal(InspectionSpec spec, Operations operations) {
    this.spec = spec;
    this.operations = operations;
  }

  @Override
  public Inspected inspect(Occluded<?> occluded) {
    return operations.inspecting().inspect(occluded, spec);
  }

  @Override
  public String toString() {
    return "inspection '" + spec.name() + "'";
  }
}
