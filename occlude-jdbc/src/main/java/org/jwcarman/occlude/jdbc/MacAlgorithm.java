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

/**
 * What a store signs its values and its record with.
 *
 * <p>Recorded on every row, so moving to another is rotating to a root signed with it: what the old
 * one signed still verifies under the algorithm it names. A short list rather than any name the JCA
 * knows, because the name is read back from the database, and a verifier that accepted whatever a
 * row said could be talked down to something weaker by rewriting one column.
 */
public enum MacAlgorithm {
  /** HMAC with SHA-256: the default, and mandatory on every Java platform. */
  HMAC_SHA256("HmacSHA256"),
  /** HMAC with SHA-384. */
  HMAC_SHA384("HmacSHA384"),
  /** HMAC with SHA-512. */
  HMAC_SHA512("HmacSHA512");

  private final String jcaName;

  MacAlgorithm(String jcaName) {
    this.jcaName = jcaName;
  }

  /** The name the JCA and the stored rows know it by. */
  public String jcaName() {
    return jcaName;
  }

  /** The algorithm a row names, or null when it names none this store will verify with. */
  static MacAlgorithm named(String jcaName) {
    for (MacAlgorithm algorithm : values()) {
      if (algorithm.jcaName.equals(jcaName)) {
        return algorithm;
      }
    }
    return null;
  }
}
