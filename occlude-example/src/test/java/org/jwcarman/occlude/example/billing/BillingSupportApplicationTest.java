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

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

/** The entry point hands its arguments to Spring and nothing else. */
@DisplayName("The billing support application")
class BillingSupportApplicationTest {

  @Test
  @DisplayName("starts Spring with its own class and the arguments it was given")
  void starts_spring_with_its_arguments() {
    String[] args = {"--server.port=0"};
    try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
      BillingSupportApplication.main(args);

      spring.verify(() -> SpringApplication.run(BillingSupportApplication.class, args));
    }
  }
}
