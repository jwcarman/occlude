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
package org.jwcarman.occlude.example.billing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** The demo secrets are the demo profile's, and nothing else picks them up by accident. */
@DisplayName("The example outside its demo profile")
class DemoProfileTest {

  @Test
  @DisplayName("refuses to start, saying it has no keys, rather than running on committed secrets")
  void refuses_to_start_without_the_demo_profile() {
    SpringApplicationBuilder example =
        new SpringApplicationBuilder(BillingSupportApplication.class)
            .web(WebApplicationType.NONE)
            .properties("occlude.migrate=false", "spring.main.banner-mode=off");

    assertThatThrownBy(example::run).hasStackTraceContaining("has no keys to do it with");
  }
}
