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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Label;

@DisplayName("A value on its way to storage")
class StoredValueTest {

  private static final Axis<String> TENANT = Axis.matching("tenant");

  /** It passes through every storage implementation, which is where things end up in logs. */
  @Test
  @DisplayName("says what type it is and never what it holds")
  void never_prints_what_it_holds() {
    StoredValue value =
        new StoredValue(
            "4111111111114821",
            OccludedType.of(String.class),
            Label.of(TENANT, "acme"),
            Lineage.occluded());

    assertThat(value.toString()).contains("string").doesNotContain("4111111111114821");
  }
}
