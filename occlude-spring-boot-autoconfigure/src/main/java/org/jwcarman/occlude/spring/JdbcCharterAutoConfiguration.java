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

import io.micrometer.observation.ObservationRegistry;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.crypto.DataKeyProvider;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.occlude.Charter;
import org.jwcarman.occlude.jdbc.AuditTrail;
import org.jwcarman.occlude.jdbc.JdbcStorage;
import org.jwcarman.occlude.jdbc.JdbcStorageConfig;
import org.jwcarman.occlude.jdbc.MacAlgorithm;
import org.jwcarman.occlude.jdbc.StorageIntegrity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import tools.jackson.databind.json.JsonMapper;

/**
 * A store kept in a database, wired when that module is on the classpath.
 *
 * <p>Takes the lifecycle off the application. Building a store is the moment its access space is
 * fixed: every capability declared beforehand is attached, and anything declared afterwards reaches
 * nothing. So the build must come last, after every bean that declares a portal has been
 * constructed -- which by hand means one class orchestrating the whole startup.
 *
 * <p>Here it is Spring's job. Declare an {@code Axes} bean, take the {@code Charter} as a parameter
 * wherever you declare portals, and this supplies the plumbing and builds the store.
 *
 * <p>The store is registered as no candidate for injection by type, so no application bean can take
 * one and read around its portals. What operating it needs -- verifying, anchoring, re-encrypting
 * -- is published instead as {@link StorageIntegrity}, which reads no value.
 *
 * <p>Everything it supplies is {@link ConditionalOnMissingBean}, so any of it can be replaced by
 * declaring your own: the serialisation, the keys, or the data source itself. What cannot be
 * replaced is that everything is encrypted and signed: a store is not built without keys and a
 * root, and startup says which is missing.
 */
@AutoConfiguration(
    after = CharterAutoConfiguration.class,
    // Named rather than referenced: Boot 4 moved this into its own module, and naming it keeps
    // that module off our compile path.
    afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({JdbcStorage.class, DataSource.class})
public class JdbcCharterAutoConfiguration {

  /** Created by Spring Boot's auto-configuration. */
  public JdbcCharterAutoConfiguration() {
    // The store and what it needs are the beans below.
  }

  /** The name the audit trail is registered, and found, under. */
  public static final String AUDIT_TRAIL = "occludeAuditTrail";

  /**
   * How values are serialised, before they are encrypted.
   *
   * @return the factory that makes a codec for each occluded type
   */
  @Bean
  @ConditionalOnMissingBean
  public CodecFactory occludedCodecFactory() {
    return new JacksonCodecFactory(JsonMapper.builder().build());
  }

  /**
   * Key-encryption keys from configuration, for an application that has no KMS.
   *
   * <p>Only when {@code occlude.keys.current} is set, and never when the application contributes
   * its own {@link DataKeyProvider}: a KMS or Vault replaces this entirely. Nothing here generates
   * a key or has one to fall back on.
   *
   * @param properties the configured keys
   * @return a provider of the configured key-encryption keys, current one first
   */
  @Bean
  @ConditionalOnMissingBean(DataKeyProvider.class)
  @ConditionalOnProperty(prefix = "occlude.keys", name = "current")
  public DataKeyProvider occludedDataKeys(CharterProperties properties) {
    CharterProperties.Keys keys = properties.getKeys();
    Map<String, SecretKey> keks = new LinkedHashMap<>();
    keys.getKeks()
        .forEach(
            (id, encoded) -> keks.put(id, new SecretKeySpec(decoded(encoded, "key", id), "AES")));
    return new JceDataKeyProvider(keys.getCurrent(), keks);
  }

  /**
   * The store and its integrity, together or not at all.
   *
   * <p>Decided once, by name, for both. Spring Boot's bean conditions pass over a bean that is no
   * candidate for injection by type, which is exactly how a store is registered -- so "is there a
   * store yet" can only be asked by name, and asked before either bean exists.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean(Charter.class)
  @ConditionalOnMissingBean(name = CharterAutoConfiguration.STORAGE)
  static class Store {

    /**
     * Durable storage for whatever charter this application declared.
     *
     * <p>This module's whole job. It supplies somewhere to keep values and lines; it does not
     * construct a charter and it does not bring one into force, so nothing here decides what an
     * application is allowed to do.
     *
     * <p>Keys are required, not looked for: without a {@link DataKeyProvider} -- the application's,
     * or the one built from {@code occlude.keys.*} -- the application fails to start with Spring's
     * own report of the missing bean. A root is required the same way.
     */
    @Bean(name = CharterAutoConfiguration.STORAGE, defaultCandidate = false)
    public JdbcStorage jdbcStorage(
        Charter charter,
        DataSource dataSource,
        CodecFactory codecs,
        DataKeyProvider keys,
        CharterProperties properties,
        ObjectProvider<JdbcStorageConfigCustomizer> customizers) {
      CharterProperties.Roots roots = properties.getRoots();
      if (roots.getCurrent() == null) {
        throw new IllegalStateException(
            "occlude-jdbc signs its record and its values under a secret root and has none: set"
                + " occlude.roots.current and occlude.roots.secrets.<id>");
      }
      Map<String, byte[]> secrets = new LinkedHashMap<>();
      roots.getSecrets().forEach((id, encoded) -> secrets.put(id, decoded(encoded, "root", id)));
      if (!secrets.containsKey(roots.getCurrent())) {
        throw new IllegalStateException(
            "occlude.roots.current is '"
                + roots.getCurrent()
                + "', but occlude.roots.secrets has no secret by that name");
      }
      JdbcStorageConfig jdbc =
          new JdbcStorageConfig()
              .dataSource(outsideAnyTransaction(dataSource))
              .codecs(codecs)
              .encryptedWith(keys)
              .rootedIn(roots.getCurrent(), secrets::get)
              .signedWith(macNamed(roots.getMac()));
      if (!properties.isMigrate()) {
        jdbc.withoutMigration();
      }
      customizers.orderedStream().forEach(customizer -> customizer.customize(jdbc));
      return jdbc.storage(charter.axes());
    }

    /**
     * Verification, anchoring and re-encryption of the store above, for operations code: a
     * scheduled sweep, an endpoint that publishes the trail's head. It reads no value, so unlike
     * the store it is an ordinary bean.
     */
    @Bean
    public StorageIntegrity storageIntegrity(
        @Qualifier(CharterAutoConfiguration.STORAGE) JdbcStorage storage) {
      return storage.integrity();
    }

    /**
     * The trail read back, for investigation code.
     *
     * <p>Registered like the store: by name, and no candidate for injection by type. It discloses
     * every line's label and who was asking, across every value -- an authority to be asked for
     * deliberately, with {@code @Qualifier(JdbcCharterAutoConfiguration.AUDIT_TRAIL)}, never one a
     * bean acquires by naming a type in its constructor.
     */
    @Bean(name = AUDIT_TRAIL, defaultCandidate = false)
    public AuditTrail occludeAuditTrail(
        @Qualifier(CharterAutoConfiguration.STORAGE) JdbcStorage storage) {
      return storage.trail();
    }

    /**
     * The store checked on a schedule, when {@code occlude.integrity.interval} says how often.
     *
     * <p>Each run is observed through Spring Boot's registry, like every operation.
     */
    @Bean
    @ConditionalOnProperty(prefix = "occlude.integrity", name = "interval")
    public IntegrityMonitor integrityMonitor(
        StorageIntegrity integrity,
        CharterProperties properties,
        ObservationRegistry observations) {
      return new IntegrityMonitor(integrity, properties.getIntegrity().getInterval(), observations);
    }

    /**
     * A signing algorithm by name, and a message listing the choices rather than a bare enum error.
     */
    private static MacAlgorithm macNamed(String name) {
      return Arrays.stream(MacAlgorithm.values())
          .filter(algorithm -> algorithm.name().equals(name))
          .findFirst()
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "occlude.roots.mac is '"
                          + name
                          + "'; it must be one of "
                          + Arrays.toString(MacAlgorithm.values())));
    }
  }

  /**
   * The data source itself, never one that hands out the current transaction's connection.
   *
   * <p>Every act the store performs commits on its own, because the record must outlive whatever
   * the application's transaction does next. Given Spring's transaction-aware proxy, the store
   * would be handed the application's transaction instead, and commit its work early.
   */
  static DataSource outsideAnyTransaction(DataSource dataSource) {
    DataSource unwrapped = dataSource;
    while (unwrapped instanceof TransactionAwareDataSourceProxy proxy) {
      unwrapped = proxy.getTargetDataSource();
    }
    // One further in cannot be unwrapped without losing whatever wraps it -- credentials, a lazy
    // connection -- so it is refused, rather than letting the store be handed the transaction.
    DataSource inner = unwrapped;
    while (inner instanceof DelegatingDataSource delegating) {
      inner = delegating.getTargetDataSource();
      if (inner instanceof TransactionAwareDataSourceProxy) {
        throw new IllegalStateException(
            "the DataSource wraps a TransactionAwareDataSourceProxy inside another wrapper, so"
                + " occlude would be handed the application's transaction and commit it early."
                + " Give occlude a DataSource outside any transaction.");
      }
    }
    return unwrapped;
  }

  /** Base64, and a message naming which entry was not, rather than a bare decoder exception. */
  private static byte[] decoded(String encoded, String what, String id) {
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(what + " '" + id + "' is not valid base64", e);
    }
  }
}
