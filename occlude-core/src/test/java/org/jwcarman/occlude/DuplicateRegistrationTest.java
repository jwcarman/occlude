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
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;

/**
 * Two declarations that cannot both be registered under the same name, found the moment the second
 * is declared rather than the first time a reader picks the wrong one.
 *
 * <p>Nothing looks a declaration up by its name -- every portal carries its own -- but a name is
 * what the record and the manifest say, and two doors answering to one name make both lie.
 */
@DisplayName("Two declarations claiming the same name")
class DuplicateRegistrationTest {

  private static final OccludedType<String> STRING_TYPE = OccludedType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  @Test
  @DisplayName("two sinks registered as the same name are refused when declared")
  void two_sinks_with_the_same_name_are_refused() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    charter.sink("outbox", ctx -> Ceiling.nothing(), STRING_TYPE);
    Ceiling anything = Ceiling.of(TENANT, Constraint.any());

    assertThatThrownBy(() -> charter.sink("outbox", anything, STRING_TYPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outbox");
  }

  @Test
  @DisplayName("two derivations registered as the same name are refused when declared")
  void two_derivations_with_the_same_name_are_refused() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    charter.derivation(
        "upper",
        STRING_TYPE,
        STRING_TYPE,
        String::toUpperCase,
        d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
    assertThatThrownBy(
            () ->
                charter.derivation(
                    "upper",
                    STRING_TYPE,
                    STRING_TYPE,
                    String::toLowerCase,
                    d -> d.accepting(Ceiling.of(TENANT, Constraint.any()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("upper");
  }

  @Test
  @DisplayName("two questions registered as the same name are refused when declared")
  void two_questions_with_the_same_name_are_refused() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    charter.query(
        "mentions",
        STRING_TYPE,
        String.class,
        (v, q, ctx) -> true,
        d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

    assertThatThrownBy(
            () ->
                charter.query(
                    "mentions",
                    STRING_TYPE,
                    String.class,
                    (v, q, ctx) -> false,
                    d -> d.accepting(Ceiling.of(TENANT, Constraint.any()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("two queries are registered as 'mentions'");
  }

  @Test
  @DisplayName("two inspections registered as the same name are refused when declared")
  void two_inspections_with_the_same_name_are_refused() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    Ceiling anything = Ceiling.of(TENANT, Constraint.any());
    charter.inspection("desk", anything);

    assertThatThrownBy(() -> charter.inspection("desk", anything))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("two inspections are registered as 'desk'");
  }
}
