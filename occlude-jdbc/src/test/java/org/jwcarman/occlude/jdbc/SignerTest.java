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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What signing does on a JVM that cannot compute the MAC a store is configured with. */
@DisplayName("A signer")
class SignerTest {

  /**
   * HMAC-SHA-256 is mandatory on every Java SE platform, so this is a JVM with its JCE provider
   * removed -- a stripped-down runtime, a hardened container. The provider is put back exactly
   * where it was, because it is global to the JVM.
   */
  @Test
  @DisplayName("says so plainly on a JVM that cannot compute its MAC")
  void says_so_on_a_jvm_that_cannot_compute_its_mac() {
    Signer signer = new Signer(TestKeys.ROOT_ID, id -> TestKeys.root(), MacAlgorithm.HMAC_SHA256);
    Provider jce = Security.getProvider("SunJCE");
    int position = Arrays.asList(Security.getProviders()).indexOf(jce) + 1;
    assertThat(position).isPositive();
    Security.removeProvider(jce.getName());
    try {
      assertThatThrownBy(
              () -> signer.keyed(Signer.Domain.VALUE, TestKeys.ROOT_ID, MacAlgorithm.HMAC_SHA256))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot compute HmacSHA256");
    } finally {
      Security.insertProviderAt(jce, position);
    }
    assertThat(signer.keyed(Signer.Domain.VALUE, TestKeys.ROOT_ID, MacAlgorithm.HMAC_SHA256))
        .isNotNull();
  }
}
