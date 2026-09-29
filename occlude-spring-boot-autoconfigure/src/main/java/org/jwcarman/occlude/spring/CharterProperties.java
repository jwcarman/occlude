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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** What the store needs told that is not policy: everything under {@code occlude.*}. */
@ConfigurationProperties(prefix = "occlude")
public class CharterProperties {

  /** Created and filled in by Spring Boot's configuration binding. */
  public CharterProperties() {
    // Every property has its default where it is declared.
  }

  /** Whether to create the tables at startup if they are not there. */
  private boolean migrate = true;

  /**
   * Whether to create the tables at startup.
   *
   * @return true unless told otherwise
   */
  public boolean isMigrate() {
    return migrate;
  }

  /**
   * Whether to create the tables at startup.
   *
   * @param migrate false for somewhere that manages its own schema
   */
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

  /**
   * Whether to log the manifest at startup.
   *
   * @return true unless told otherwise
   */
  public boolean isLogManifest() {
    return logManifest;
  }

  /**
   * Whether to log the manifest at startup.
   *
   * @param logManifest false to keep it out of the log
   */
  public void setLogManifest(boolean logManifest) {
    this.logManifest = logManifest;
  }

  /** Checking the store on a schedule. */
  private final Integrity integrity = new Integrity();

  /**
   * Checking the store on a schedule: {@code occlude.integrity.*}.
   *
   * @return the settings, never null
   */
  public Integrity getIntegrity() {
    return integrity;
  }

  /** Key-encryption keys for stored values, when the application does not contribute its own. */
  private final Keys keys = new Keys();

  /**
   * Key-encryption keys: {@code occlude.keys.*}.
   *
   * @return the settings, never null
   */
  public Keys getKeys() {
    return keys;
  }

  /** Secrets the record and the values are signed under. */
  private final Roots roots = new Roots();

  /**
   * Root secrets: {@code occlude.roots.*}.
   *
   * @return the settings, never null
   */
  public Roots getRoots() {
    return roots;
  }

  /** How often the store checks itself; never, unless an interval is set. */
  public static class Integrity {

    /** Created and filled in by Spring Boot's configuration binding. */
    public Integrity() {
      // Unset until configured: nothing is scheduled.
    }

    /**
     * How long between checks. Unset, nothing is scheduled: a check reads and decrypts every row,
     * and how often that is affordable depends on the store.
     */
    private Duration interval;

    /**
     * How long between checks.
     *
     * @return the interval, or null when no check is scheduled
     */
    public Duration getInterval() {
      return interval;
    }

    /**
     * How long between checks.
     *
     * @param interval the interval; setting one schedules the check
     */
    public void setInterval(Duration interval) {
      this.interval = interval;
    }
  }

  /**
   * AES-256 key-encryption keys by id, base64-encoded, and which one new values are wrapped under.
   *
   * <p>A convenience for an application without a KMS: a {@code DataKeyProvider} bean replaces it
   * entirely. Keys belong in the environment or a secret store, never in a committed file. Rotating
   * is adding one and making it current; what the others wrapped still decrypts.
   */
  public static class Keys {

    /** Created and filled in by Spring Boot's configuration binding. */
    public Keys() {
      // Unset until configured: without keys, the JDBC store is not built.
    }

    /** The id of the key new values are wrapped under; it must be one of the keys given. */
    private String current;

    /** Every key-encryption key by id, base64-encoded AES-256. */
    private Map<String, String> keks = new LinkedHashMap<>();

    /**
     * The id of the key new values are wrapped under.
     *
     * @return the id, or null when no keys are configured
     */
    public String getCurrent() {
      return current;
    }

    /**
     * The id of the key new values are wrapped under.
     *
     * @param current an id among the keys given
     */
    public void setCurrent(String current) {
      this.current = current;
    }

    /**
     * Every key-encryption key by id.
     *
     * @return base64-encoded AES-256 keys by id
     */
    public Map<String, String> getKeks() {
      return keks;
    }

    /**
     * Every key-encryption key by id.
     *
     * @param keks base64-encoded AES-256 keys by id
     */
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

    /** Created and filled in by Spring Boot's configuration binding. */
    public Roots() {
      // Unset until configured: without a root, the JDBC store is not built.
    }

    /** The id of the root new values and lines are signed under; one of the secrets given. */
    private String current;

    /** Every root secret by id, base64-encoded, each at least 32 bytes. */
    private Map<String, String> secrets = new LinkedHashMap<>();

    /**
     * What new values and lines are signed with: HMAC_SHA256, HMAC_SHA384 or HMAC_SHA512. Each row
     * records its own, so a change applies to what comes next; pair it with a new root.
     */
    private String mac = "HMAC_SHA256";

    /**
     * The id of the root new values and lines are signed under.
     *
     * @return the id, or null when no root is configured
     */
    public String getCurrent() {
      return current;
    }

    /**
     * The id of the root new values and lines are signed under.
     *
     * @param current an id among the secrets given
     */
    public void setCurrent(String current) {
      this.current = current;
    }

    /**
     * Every root secret by id.
     *
     * @return base64-encoded secrets by id
     */
    public Map<String, String> getSecrets() {
      return secrets;
    }

    /**
     * Every root secret by id.
     *
     * @param secrets base64-encoded secrets by id, each at least 32 bytes
     */
    public void setSecrets(Map<String, String> secrets) {
      this.secrets = secrets;
    }

    /**
     * What new values and lines are signed with.
     *
     * @return HMAC_SHA256 unless told otherwise
     */
    public String getMac() {
      return mac;
    }

    /**
     * What new values and lines are signed with.
     *
     * @param mac HMAC_SHA256, HMAC_SHA384 or HMAC_SHA512
     */
    public void setMac(String mac) {
      this.mac = mac;
    }
  }
}
