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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("An access context")
class AccessContextTest {

  /** Six keys in an order no hash would produce by accident, so a reordering cannot pass. */
  private static Map<String, String> ordered() {
    Map<String, String> ordered = new LinkedHashMap<>();
    ordered.put("tenant", "acme");
    ordered.put("role", "compliance");
    ordered.put("user", "jwcarman");
    ordered.put("clearance", "cardholder");
    ordered.put("region", "us-east");
    ordered.put("approver", "yes");
    return ordered;
  }

  @Test
  @DisplayName("holds nobody in particular when empty")
  void holds_nobody_in_particular_when_empty() {
    assertThat(AccessContext.empty().attributes()).isEmpty();
  }

  @Test
  @DisplayName("holds the one attribute it was built from")
  void holds_the_one_attribute_it_was_built_from() {
    AccessContext context = AccessContext.of("tenant", "acme");

    assertThat(context.get("tenant")).contains("acme");
  }

  @Test
  @DisplayName("holds whatever map it was built from")
  void holds_whatever_map_it_was_built_from() {
    AccessContext context = AccessContext.of(Map.of("tenant", "acme", "clearance", "cardholder"));

    assertThat(context.get("tenant")).contains("acme");
    assertThat(context.get("clearance")).contains("cardholder");
  }

  @Test
  @DisplayName("answers empty for an attribute it was never given")
  void answers_empty_for_an_unset_attribute() {
    AccessContext context = AccessContext.empty();

    assertThat(context.get("tenant")).isEmpty();
  }

  @Test
  @DisplayName("says an attribute has a value only when it matches exactly")
  void says_an_attribute_has_a_value_only_when_it_matches() {
    AccessContext context = AccessContext.of("tenant", "acme");

    assertThat(context.has("tenant", "acme")).isTrue();
    assertThat(context.has("tenant", "globex")).isFalse();
  }

  @Test
  @DisplayName("says an unset attribute has no value, whatever is asked")
  void says_an_unset_attribute_has_no_value() {
    AccessContext context = AccessContext.empty();

    assertThat(context.has("tenant", "acme")).isFalse();
  }

  /**
   * The map handed to {@code of} is not held by reference -- a caller mutating its own map
   * afterward must not reach back into an already-built context.
   */
  @Test
  @DisplayName("copies the map it is built from, rather than holding it by reference")
  void copies_the_map_it_is_built_from() {
    Map<String, String> mutable = new HashMap<>();
    mutable.put("tenant", "acme");

    AccessContext context = AccessContext.of(mutable);
    mutable.put("tenant", "globex");
    mutable.put("clearance", "cardholder");

    assertThat(context.get("tenant")).contains("acme");
    assertThat(context.get("clearance")).isEmpty();
  }

  /**
   * An access context is what an audit line records, so its attributes are read back in the order
   * the edge wrote them rather than in whatever order a hash happens to produce this run.
   */
  @Test
  @DisplayName("keeps its attributes in the order it was given them")
  void keeps_its_attributes_in_the_order_it_was_given_them() {
    AccessContext context = AccessContext.of(ordered());

    assertThat(context.attributes().keySet())
        .containsExactly("tenant", "role", "user", "clearance", "region", "approver");
  }

  @Test
  @DisplayName("cannot be changed through the map it hands out")
  void cannot_be_changed_through_the_map_it_hands_out() {
    Map<String, String> attributes = AccessContext.of(ordered()).attributes();

    assertThatThrownBy(() -> attributes.put("tenant", "globex"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("refuses a null attribute value")
  void refuses_a_null_attribute_value() {
    Map<String, String> withNull = new HashMap<>();
    withNull.put("tenant", null);

    assertThatThrownBy(() -> AccessContext.of(withNull)).isInstanceOf(NullPointerException.class);
  }
}
