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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("An erasure's outcome")
class ErasedTest {

  @Nested
  @DisplayName("that removed something")
  class ThatRemovedSomething {

    private final Erased erased = new Erased.Removed(3);

    @Test
    @DisplayName("succeeded")
    void succeeded() {
      assertThat(erased.succeeded()).isTrue();
    }

    @Test
    @DisplayName("says how many values went, the root included")
    void says_how_many_went() {
      assertThat(erased.removed()).hasValue(3);
    }

    @Test
    @DisplayName("hands back the count rather than throwing")
    void hands_back_the_count_rather_than_throwing() {
      assertThat(erased.orThrow()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("that was refused")
  class ThatWasRefused {

    private final Erased erased =
        new Erased.Refused(Erased.Reason.NOT_PERMITTED, "'retention' may not erase occ_1");

    @Test
    @DisplayName("did not succeed")
    void did_not_succeed() {
      assertThat(erased.succeeded()).isFalse();
    }

    @Test
    @DisplayName("says nothing was removed rather than zero")
    void says_nothing_was_removed() {
      assertThat(erased.removed()).isEmpty();
    }

    @Test
    @DisplayName("throws the refusal, naming its reason, when the caller insists")
    void throws_the_refusal() {
      assertThatThrownBy(erased::orThrow)
          .isInstanceOf(RefusedException.class)
          .hasMessageContaining("NOT_PERMITTED")
          .hasMessageContaining("retention");
    }
  }
}
