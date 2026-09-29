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
package org.jwcarman.occlude.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** What the store needs told that is not policy. */
@ConfigurationProperties(prefix = "occlude")
public class CharterProperties {

  /** Whether to create the tables at startup if they are not there. */
  private boolean migrate = true;

  public boolean isMigrate() {
    return migrate;
  }

  public void setMigrate(boolean migrate) {
    this.migrate = migrate;
  }

  /**
   * Whether to write what this application allows into the log at startup.
   *
   * <p>On by default. The manifest is the whole access space in one page -- every door, what it
   * accepts, and which operations weaken a label -- and it is worth somebody seeing it go past when
   * it changes.
   */
  private boolean logManifest = true;

  public boolean isLogManifest() {
    return logManifest;
  }

  public void setLogManifest(boolean logManifest) {
    this.logManifest = logManifest;
  }

  /** Key-encryption keys for stored values, when the application does not contribute its own. */
  private final Keys keys = new Keys();

  public Keys getKeys() {
    return keys;
  }

  /** Secrets the record and the values are signed under. */
  private final Roots roots = new Roots();

  public Roots getRoots() {
    return roots;
  }

  /**
   * AES-256 key-encryption keys by id, base64-encoded, and which one new values are wrapped under.
   *
   * <p>A convenience for an application without a KMS: a {@code DataKeyProvider} bean replaces it
   * entirely. Keys belong in the environment or a secret store, never in a committed file. Rotating
   * is adding one and making it current; what the others wrapped still decrypts.
   */
  public static class Keys {

    private String current;
    private Map<String, String> keks = new LinkedHashMap<>();

    public String getCurrent() {
      return current;
    }

    public void setCurrent(String current) {
      this.current = current;
    }

    public Map<String, String> getKeks() {
      return keks;
    }

    public void setKeks(Map<String, String> keks) {
      this.keks = keks;
    }
  }

  /**
   * Root secrets by id, base64-encoded, and which one new values and lines are signed under.
   *
   * <p>Each value and line records the root it was written under, so a new one takes over without
   * invalidating what the others signed -- supply them all.
   */
  public static class Roots {

    private String current;
    private Map<String, String> secrets = new LinkedHashMap<>();

    /**
     * What new values and lines are signed with: HMAC_SHA256, HMAC_SHA384 or HMAC_SHA512. Each row
     * records its own, so a change applies to what comes next; pair it with a new root.
     */
    private String mac = "HMAC_SHA256";

    public String getCurrent() {
      return current;
    }

    public void setCurrent(String current) {
      this.current = current;
    }

    public Map<String, String> getSecrets() {
      return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
      this.secrets = secrets;
    }

    public String getMac() {
      return mac;
    }

    public void setMac(String mac) {
      this.mac = mac;
    }
  }
}
