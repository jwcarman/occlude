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
import org.jwcarman.occlude.lattice.Label;

/**
 * A sink name {@link Revealing} is asked for but never registered.
 *
 * <p>Nothing in the public surface can produce this: a {@link Reveal} is minted by {@link
 * DefaultCharter#sink} closed over a name that was just added to the same map {@code Revealing}
 * checks, so every {@code to} a real caller can supply is already a key in it. The check still
 * exists because {@link Revealing#reveal} is a general-purpose method with one caller today and no
 * promise of staying that way, so this reaches it directly, the one time in this suite {@code
 * Revealing} is not driven through a portal.
 */
@DisplayName("Revealing asked for a sink nobody registered")
class RevealViaUnregisteredSinkTest {

  private static final OccludedType<String> STRING_TYPE = OccludedType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  @Test
  @DisplayName("is denied as NO_SUCH_SINK rather than throwing")
  void is_denied_as_no_such_sink() {
    DefaultCharter charter = new DefaultCharter(TENANT);
    Occlude<String> source = charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
    charter.seal(new MemoryStorage());
    Occluded<String> held = source.occlude("hello");

    Revealed<String> result =
        charter
            .lifecycle()
            .operations()
            .revealing()
            .reveal(held, STRING_TYPE, "nobody-registered-this");

    assertThat(result)
        .isInstanceOfSatisfying(
            Revealed.Denied.class,
            denied -> assertThat(denied.reason()).isEqualTo(Revealed.Reason.NO_SUCH_SINK));
  }
}
