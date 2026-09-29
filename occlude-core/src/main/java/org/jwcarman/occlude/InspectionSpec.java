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

import java.util.function.Function;
import org.jwcarman.occlude.lattice.Ceiling;

/** Everything the engine needs to answer one inspection. Package-private, like every other spec. */
record InspectionSpec(String name, Function<AccessContext, Ceiling> ceiling) {

  /** What this may look at for this access. */
  Ceiling ceilingFor(AccessContext context) {
    return ceiling.apply(context);
  }
}
