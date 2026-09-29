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

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.jwcarman.codec.crypto.DataKeyProvider;
import org.jwcarman.codec.crypto.JceDataKeyProvider;

/**
 * Keys for tests that need a real store.
 *
 * <p>A store cannot be built without encryption and a secret root, so a test builds one the way an
 * application would, with a key nobody else holds: generated here, per run.
 */
final class TestKeys {

  static final String ROOT_ID = "test";

  private static final byte[] ROOT = "a root this test holds".getBytes(StandardCharsets.UTF_8);

  private TestKeys() {}

  /** A fresh AES-256 key-encryption key under id "test". */
  static DataKeyProvider dataKeys() {
    return new JceDataKeyProvider("test", Map.of("test", aes256()));
  }

  static byte[] root() {
    return ROOT.clone();
  }

  static SecretKey aes256() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("this JVM cannot make an AES key", e);
    }
  }
}
