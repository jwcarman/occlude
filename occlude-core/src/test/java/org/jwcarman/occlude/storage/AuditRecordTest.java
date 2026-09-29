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
package org.jwcarman.occlude.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One line of the record, readable on its own rather than only through a store's audit trail. */
@DisplayName("An audit record")
class AuditRecordTest {

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

  private AuditRecord allowed(Map<String, String> context) {
    return new AuditRecord(
        AuditRecord.Operation.REVEAL,
        "occ_1",
        Optional.of("vendor-llm"),
        AuditRecord.Outcome.ALLOWED,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        context);
  }

  /** Printed into a log, a record must not become a second, unprotected copy of the trail. */
  @Test
  @DisplayName("prints what the trail keeps in the clear, and never the label or who asked")
  void prints_only_what_the_trail_keeps_in_the_clear() {
    AuditRecord entry =
        new AuditRecord(
            AuditRecord.Operation.REVEAL,
            "occ_1",
            Optional.of("vendor-llm"),
            AuditRecord.Outcome.REFUSED,
            Optional.of("ABOVE_CEILING"),
            Optional.of("{tenant=acme} is above {tenant=globex}"),
            Optional.of("{tenant=acme}"),
            Map.of("tenant", "acme", "principal", "dana"));

    assertThat(entry).hasToString("REVEAL occ_1 -> vendor-llm REFUSED: ABOVE_CEILING");
    assertThat(allowed(Map.of())).hasToString("REVEAL occ_1 -> vendor-llm ALLOWED");
  }

  /** A line names who asked the way the edge said it, not in a hash order that varies by run. */
  @Test
  @DisplayName("says who asked in the order the context was given")
  void says_who_asked_in_the_order_the_context_was_given() {
    AuditRecord entry = allowed(ordered());

    assertThat(entry.context().keySet())
        .containsExactly("tenant", "role", "user", "clearance", "region", "approver");
  }
}
