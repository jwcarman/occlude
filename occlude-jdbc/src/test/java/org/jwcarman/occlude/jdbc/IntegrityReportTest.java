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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What one pass of verification concludes, from each thing it can find. */
@DisplayName("An integrity report")
class IntegrityReportTest {

  private static final Sweep CLEAN = new Sweep(List.of(), List.of(), List.of(), List.of());

  private static IntegrityReport report(
      Optional<Long> brokenAt, List<String> broken, List<String> missing, Sweep sweep) {
    return new IntegrityReport(brokenAt, broken, missing, sweep, Optional.empty());
  }

  @Test
  @DisplayName("is intact and readable when nothing was found")
  void is_intact_when_nothing_was_found() {
    IntegrityReport report = report(Optional.empty(), List.of(), List.of(), CLEAN);

    assertThat(report.intact()).isTrue();
    assertThat(report.unreadable()).isFalse();
  }

  @Test
  @DisplayName("is not intact when any one check found something")
  void is_not_intact_when_any_check_found_something() {
    assertThat(report(Optional.of(7L), List.of(), List.of(), CLEAN).intact()).isFalse();
    assertThat(report(Optional.empty(), List.of("occ_a"), List.of(), CLEAN).intact()).isFalse();
    assertThat(report(Optional.empty(), List.of(), List.of("occ_b"), CLEAN).intact()).isFalse();
    Sweep altered = new Sweep(List.of("occ_c"), List.of(), List.of(), List.of());
    assertThat(report(Optional.empty(), List.of(), List.of(), altered).intact()).isFalse();
  }

  /** Unreadable is a fact about the keys at hand, so it is reported apart from intact. */
  @Test
  @DisplayName("is unreadable, and still intact, when a value or a line would not decrypt")
  void is_unreadable_apart_from_intact() {
    Sweep values = new Sweep(List.of(), List.of("occ_d"), List.of(), List.of());
    Sweep lines = new Sweep(List.of(), List.of(), List.of(), List.of(3L));

    assertThat(report(Optional.empty(), List.of(), List.of(), values).unreadable()).isTrue();
    assertThat(report(Optional.empty(), List.of(), List.of(), values).intact()).isTrue();
    assertThat(report(Optional.empty(), List.of(), List.of(), lines).unreadable()).isTrue();
  }
}
