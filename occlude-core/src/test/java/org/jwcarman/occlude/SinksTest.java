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
 * A sink declared outside a charter, the way {@link DefaultCharter} never declares one itself -- it
 * only ever registers the {@code varying} shape. {@code fixed} is for an application that builds a
 * {@link SinkSpec} to hand to {@link Charter#sink(SinkSpec)}.
 */
@DisplayName("A sink declared with Sinks")
class SinksTest {

  private static final Axis<String> TENANT = Axis.matching("tenant");

  @Test
  @DisplayName("fixed refuses a null name")
  void fixed_refuses_a_null_name() {
    Ceiling ceiling = Ceiling.nothing();

    assertThatThrownBy(() -> Sinks.fixed(null, ceiling)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("fixed refuses a null ceiling")
  void fixed_refuses_a_null_ceiling() {
    assertThatThrownBy(() -> Sinks.fixed("somewhere", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("fixed names itself and hands back the same ceiling regardless of who is asking")
  void fixed_names_itself_and_ignores_who_is_asking() {
    Ceiling ceiling = Ceiling.of(TENANT, Constraint.any());
    SinkSpec spec = Sinks.fixed("archive", ceiling);

    assertThat(spec.name()).isEqualTo("archive");
    assertThat(spec.ceiling(AccessContext.empty())).isEqualTo(ceiling);
    assertThat(spec.ceiling(AccessContext.of("tenant", "acme"))).isEqualTo(ceiling);
  }

  /**
   * The overload a charter offers for a sink declared elsewhere: it only has to register the spec,
   * because {@code fixed} and {@code varying} already know how to be one.
   */
  @Test
  @DisplayName("is registered on a charter through the SinkSpec overload")
  void is_registered_on_a_charter_through_the_sink_spec_overload() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    SinkSpec archive = Sinks.fixed("archive", Ceiling.of(TENANT, Constraint.any()));

    Charter returned = charter.sink(archive);

    assertThat(returned).isSameAs(charter);
    assertThat(charter.sinks()).contains(archive);
  }

  @Test
  @DisplayName("refuses a null SinkSpec")
  void refuses_a_null_sink_spec() {
    DefaultCharter charter = new DefaultCharter(TENANT);

    assertThatThrownBy(() -> charter.sink((SinkSpec) null))
        .isInstanceOf(NullPointerException.class);
  }
}
