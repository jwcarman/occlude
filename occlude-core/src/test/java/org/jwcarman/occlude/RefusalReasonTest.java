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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every reason a portal can give is a reason an event can give.
 *
 * <p>Each portal's result has its own enum, which lists only what that portal can say. {@link
 * RefusalReason} lists them all. A reason added to one and not the other fails here, not at the
 * moment the trail first writes it.
 */
@DisplayName("A refusal reason")
class RefusalReasonTest {

  private static List<String> namesOf(Class<? extends Enum<?>> reasons) {
    return Arrays.stream(reasons.getEnumConstants()).map(Enum::name).toList();
  }

  @Test
  @DisplayName("exists for every reason that a portal's result can give")
  void exists_for_every_portal_reason() {
    List<String> portalReasons =
        Stream.of(
                Revealed.Reason.class,
                Derived.Reason.class,
                Answer.Reason.class,
                Erased.Reason.class,
                Inspected.Reason.class)
            .flatMap(reasons -> namesOf(reasons).stream())
            .toList();

    // Not empty, or the check below would pass while proving nothing.
    assertThat(portalReasons).isNotEmpty();
    assertThat(namesOf(RefusalReason.class)).containsAll(portalReasons);
  }

  @Test
  @DisplayName("is found again from a portal's reason")
  void is_found_from_a_portal_reason() {
    assertThat(RefusalReason.of(Erased.Reason.NOT_PERMITTED))
        .isEqualTo(RefusalReason.NOT_PERMITTED);
  }
}
