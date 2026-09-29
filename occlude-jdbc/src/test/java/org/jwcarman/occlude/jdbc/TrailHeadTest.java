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
package org.jwcarman.occlude.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** No database needed: what gets written down elsewhere, and compared later. */
@DisplayName("A trail head")
class TrailHeadTest {

  private static final byte[] DIGEST = "digest".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("is equal to another naming the same line with the same digest")
  void equals_the_same_line() {
    TrailHead head = new TrailHead(7, DIGEST);

    assertThat(head)
        .isEqualTo(new TrailHead(7, DIGEST.clone()))
        .hasSameHashCodeAs(new TrailHead(7, DIGEST));
  }

  @Test
  @DisplayName("is equal to itself and never to nothing")
  void equals_itself_and_not_null() {
    TrailHead head = new TrailHead(7, DIGEST);

    assertThat(head).isEqualTo(head).isNotEqualTo(null);
  }

  @Test
  @DisplayName("is not equal to a different line, a different digest, or something else")
  void is_unequal_otherwise() {
    TrailHead head = new TrailHead(7, DIGEST);

    assertThat(head)
        .isNotEqualTo(new TrailHead(8, DIGEST))
        .isNotEqualTo(new TrailHead(7, "other".getBytes(StandardCharsets.UTF_8)))
        .isNotEqualTo("7:6469676573");
  }

  /** Neither the caller's array nor the one handed back can change what was anchored. */
  @Test
  @DisplayName("keeps its own copy of the digest")
  void keeps_its_own_copy() {
    byte[] mine = DIGEST.clone();
    TrailHead head = new TrailHead(7, mine);

    mine[0] = 0;
    head.digest()[1] = 0;

    assertThat(head.digest()).isEqualTo(DIGEST);
  }

  @Test
  @DisplayName("prints as its position and its digest in hex, the form to write down")
  void prints_as_position_and_hex() {
    assertThat(new TrailHead(7, DIGEST)).hasToString("7:646967657374");
  }
}
