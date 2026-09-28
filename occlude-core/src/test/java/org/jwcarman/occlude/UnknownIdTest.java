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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.lattice.Axis;

/**
 * Asking a charter about a value it never minted -- not refused, because there is no policy to
 * consult; simply not here.
 */
@DisplayName("Asking about an id nobody minted")
class UnknownIdTest {

  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final DefaultCharter charter = new DefaultCharter(TENANT);

  {
    charter.seal(new MemoryStorage());
  }

  @Test
  @DisplayName("refuses to say the label of a value it is not holding")
  void refuses_to_say_the_label_of_an_id_it_is_not_holding() {
    assertThatThrownBy(() -> charter.label("occ_never-minted"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("occ_never-minted");
  }

  @Test
  @DisplayName("refuses to say the lineage of a value it is not holding")
  void refuses_to_say_the_lineage_of_an_id_it_is_not_holding() {
    assertThatThrownBy(() -> charter.lineage("occ_never-minted"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("occ_never-minted");
  }
}
