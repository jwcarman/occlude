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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.MemoryStorage;

/**
 * Every way deriving can be refused because of a parent, distinct from a ceiling that could not
 * decide: a parent that is not here, one labelled above what this derivation may read, and one of
 * the wrong type.
 */
@DisplayName("Deriving from a parent")
class DerivationEdgeCasesTest {

  private static final OccludedType<String> STRING_TYPE = OccludedType.of(String.class);
  private static final OccludedType<Integer> INT_TYPE = OccludedType.of(Integer.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final DefaultCharter charter = new DefaultCharter(TENANT);

  private final Occlude<String> globexMail =
      charter.source("globex-mail", STRING_TYPE, Label.of(TENANT, "globex"));

  private final Occlude<Integer> numbers =
      charter.source("numbers", INT_TYPE, Label.of(TENANT, "acme"));

  private final Derivation<String, String> onlyAcme =
      charter.derivation(
          "upper",
          STRING_TYPE,
          STRING_TYPE,
          String::toUpperCase,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.atMost("acme"))));

  {
    charter.bind(new MemoryStorage(), AccessContextProvider.none());
  }

  /**
   * A portal settles how many parents it takes, so this cannot be reached through one -- which is
   * exactly why it is a crash rather than a refusal: arriving here means the machinery was misused.
   */
  @Test
  @DisplayName("crashes when handed a number of parents its derivation never takes")
  void crashes_on_the_wrong_number_of_parents() {
    MemoryStorage storage = new MemoryStorage();
    Deriving deriving =
        new Deriving(
            new Gate(Axes.of(TENANT), AccessContextProvider.none()), new Trail(storage), storage);
    DerivationSpec<String> oneParent =
        new DerivationSpec<>(
            "upper",
            List.of(STRING_TYPE),
            STRING_TYPE,
            (values, context) -> Optional.of("x"),
            context -> Ceiling.of(TENANT, Constraint.any()),
            null,
            context -> true,
            false);
    List<Occluded<?>> twoParents = List.of(Occluded.of("occ_a"), Occluded.of("occ_b"));

    assertThatThrownBy(() -> deriving.derive(oneParent, twoParents))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reads 1 values and was given 2");
  }

  @Test
  @DisplayName("refuses a parent nobody is holding")
  void refuses_a_parent_nobody_is_holding() {
    Derived<String> result = onlyAcme.derive(Occluded.of("occ_never-minted"));

    assertThat(result)
        .isInstanceOfSatisfying(
            Derived.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Derived.Reason.NO_SUCH_VALUE));
  }

  @Test
  @DisplayName("refuses a parent labelled above what this derivation may read")
  void refuses_a_parent_above_the_ceiling() {
    Occluded<String> globexValue = globexMail.occlude("hello from globex");

    Derived<String> result = onlyAcme.derive(globexValue);

    assertThat(result)
        .isInstanceOfSatisfying(
            Derived.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Derived.Reason.ABOVE_CEILING));
  }

  @Test
  @DisplayName("refuses a parent of the wrong type")
  void refuses_a_parent_of_the_wrong_type() {
    Occluded<Integer> heldAsInt = numbers.occlude(42);
    Occluded<String> lying = new Occluded<>(heldAsInt.id());

    Derived<String> result = onlyAcme.derive(lying);

    assertThat(result)
        .isInstanceOfSatisfying(
            Derived.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(Derived.Reason.WRONG_TYPE));
  }
}
