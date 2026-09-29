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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.MemoryStorage;

/** Every way a question can fail to be answered, beyond a ceiling that could not decide. */
@DisplayName("Asking a question")
class QueryEdgeCasesTest {

  private static final OccludedType<String> STRING_TYPE = OccludedType.of(String.class);
  private static final OccludedType<Integer> INT_TYPE = OccludedType.of(Integer.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final DefaultCharter charter = new DefaultCharter(TENANT);

  private final Occlude<String> textSource =
      charter.source("text", STRING_TYPE, Label.of(TENANT, "acme"));

  private final Occlude<Integer> intSource =
      charter.source("numbers", INT_TYPE, Label.of(TENANT, "acme"));

  private final Query<String, String> notOfferedHere =
      charter.query(
          "not-offered-here",
          STRING_TYPE,
          String.class,
          (v, q, ctx) -> v.contains(q),
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())).availableTo(ctx -> false));

  private final Query<String, String> onlyForText =
      charter.query(
          "text.mentions",
          STRING_TYPE,
          String.class,
          (v, q, ctx) -> v.contains(q),
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

  private final Query<String, String> asksAndFails =
      charter.query(
          "always-fails",
          STRING_TYPE,
          String.class,
          (v, q, ctx) -> {
            throw new IllegalStateException("cannot read this");
          },
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

  {
    charter.bind(Bindings.of(new MemoryStorage()).withoutIdentity());
  }

  @Test
  @DisplayName("refuses a question that was not made available here")
  void refuses_a_question_not_available_here() {
    Occluded<String> held = textSource.occlude("hello");

    Answer answer = notOfferedHere.ask(held, "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Answer.Reason.NOT_AVAILABLE_HERE));
  }

  @Test
  @DisplayName("refuses a question about a value nobody is holding")
  void refuses_a_question_about_a_value_nobody_is_holding() {
    Answer answer = onlyForText.ask(Occluded.of("occ_never-minted"), "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Answer.Reason.NO_SUCH_VALUE));
  }

  @Test
  @DisplayName("refuses a question asked of a value of the wrong type")
  void refuses_a_question_asked_of_the_wrong_type() {
    Occluded<Integer> heldAsInt = intSource.occlude(42);
    Occluded<String> lying = new Occluded<>(heldAsInt.id());

    Answer answer = onlyForText.ask(lying, "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class, r -> assertThat(r.reason()).isEqualTo(Answer.Reason.WRONG_TYPE));
  }

  @Test
  @DisplayName("refuses a question whose own logic failed, after already reading the value")
  void refuses_a_question_whose_own_logic_failed() {
    Occluded<String> held = textSource.occlude("hello");

    Answer answer = asksAndFails.ask(held, "hello");

    assertThat(answer)
        .isInstanceOfSatisfying(
            Answer.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Answer.Reason.NOT_AVAILABLE_HERE));
  }
}
