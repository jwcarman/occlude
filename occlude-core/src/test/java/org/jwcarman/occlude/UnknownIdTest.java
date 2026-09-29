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
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.MemoryStorage;

/**
 * Asking about a value nobody minted -- not refused by a policy, because there is nothing to
 * consult one about; simply not here, and recorded like any other attempt.
 */
@DisplayName("Asking about an id nobody minted")
class UnknownIdTest {

  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final MemoryStorage storage = new MemoryStorage();

  private final DefaultCharter charter = new DefaultCharter(TENANT);

  private final Inspection desk = charter.inspection("desk", Ceiling.of(TENANT, Constraint.any()));

  {
    charter.bind(storage, AccessContextProvider.none());
  }

  @Test
  @DisplayName("says it is not holding a value it never minted, and records the attempt")
  void says_it_is_not_holding_an_id_it_never_minted() {
    assertThat(desk.inspect(Occluded.of("occ_never-minted")))
        .isInstanceOfSatisfying(
            Inspected.Refused.class,
            refused -> {
              assertThat(refused.reason()).isEqualTo(Inspected.Reason.NO_SUCH_VALUE);
              assertThat(refused.detail()).contains("occ_never-minted");
            });
    assertThat(storage.audit(AuditRecord.Operation.INSPECT))
        .singleElement()
        .satisfies(line -> assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }
}
