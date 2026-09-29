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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Where this example's identity comes from: request headers, since it has no identity provider. */
@DisplayName("The current access")
class CurrentAccessTest {

  private final CurrentAccess currentAccess = new CurrentAccess();

  @AfterEach
  void forgetTheRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static void during(MockHttpServletRequest request) {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @Test
  @DisplayName("is nobody in particular outside a request")
  void is_nobody_outside_a_request() {
    assertThat(currentAccess.get().attributes()).isEmpty();
  }

  @Test
  @DisplayName("is whoever the request's headers say, in the order they are read")
  void is_whoever_the_headers_say() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Tenant", "acme");
    request.addHeader("X-Role", "approver");
    request.addHeader("X-User", "dana@acme.example");
    during(request);

    assertThat(currentAccess.get().attributes())
        .containsExactly(
            entry("tenant", "acme"),
            entry("role", "approver"),
            entry("principal", "dana@acme.example"));
  }

  /** A blank header is not a tenant called "", which a ceiling might otherwise match. */
  @Test
  @DisplayName("leaves out a header that is blank or missing")
  void leaves_out_blank_headers() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Tenant", "  ");
    request.addHeader("X-Role", "agent");
    during(request);

    assertThat(currentAccess.get().attributes()).containsOnlyKeys("role");
  }

  private static Map.Entry<String, String> entry(String key, String value) {
    return Map.entry(key, value);
  }
}
