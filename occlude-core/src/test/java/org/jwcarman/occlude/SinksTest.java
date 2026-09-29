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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;

/**
 * The one shape a charter builds every sink in: a ceiling that may depend on who is asking, and
 * never answers with nothing.
 */
@DisplayName("A sink built with Sinks")
class SinksTest {

  private static final Axis<String> TENANT = Axis.matching("tenant");

  @Test
  @DisplayName("refuses a null name")
  void refuses_a_null_name() {
    assertThatThrownBy(() -> Sinks.varying(null, ctx -> Ceiling.nothing()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("refuses a null ceiling function")
  void refuses_a_null_ceiling() {
    assertThatThrownBy(() -> Sinks.varying("somewhere", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("names itself and asks its function what this access may see")
  void names_itself_and_asks_its_function() {
    Ceiling acme = Ceiling.of(TENANT, Constraint.atMost("acme"));
    SinkSpec spec =
        Sinks.varying(
            "desk", ctx -> ctx.has("tenant", "acme") ? acme : Ceiling.of(TENANT, Constraint.any()));

    assertThat(spec.name()).isEqualTo("desk");
    assertThat(spec.ceiling(AccessContext.of("tenant", "acme"))).isEqualTo(acme);
  }

  /** Null is not "no ceiling": it is a function that did not decide, and a gate treats it so. */
  @Test
  @DisplayName("refuses to hand back a ceiling that is not there")
  void refuses_to_hand_back_nothing() {
    SinkSpec spec = Sinks.varying("desk", ctx -> null);
    AccessContext nobody = AccessContext.empty();

    assertThatThrownBy(() -> spec.ceiling(nobody)).isInstanceOf(NullPointerException.class);
  }
}
