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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.crypto.DataKeyProvider;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.occlude.Charter;
import org.jwcarman.occlude.jdbc.JdbcStorage;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.storage.MemoryStorage;
import org.jwcarman.occlude.storage.Storage;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the JDBC module contributes, and what it deliberately does not.
 *
 * <p>Its whole job is somewhere to keep values and lines. It does not constitute a charter and it
 * does not bring one into force, because whichever module happens to be on the classpath should not
 * be the thing that decides when an application's authority stops growing.
 */
@Testcontainers
@DisplayName("The JDBC charter auto-configuration")
class JdbcCharterAutoConfigurationTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("charter")
          .withUsername("charter")
          .withPassword("charter");

  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CharterAutoConfiguration.class, JdbcCharterAutoConfiguration.class))
          // The schema is Postgres -- TIMESTAMPTZ is not H2 -- and it is exercised against a real
          // Postgres in occlude-jdbc. What is under test here is which beans appear and in what
          // order.
          .withPropertyValues("occlude.migrate=false")
          .withPropertyValues(KEYS_AND_ROOT);

  /**
   * A key and a root generated for this run, the way an application's environment supplies them.
   */
  private static final String[] KEYS_AND_ROOT = {
    "occlude.keys.current=k1",
    "occlude.keys.keks.k1=" + base64(32),
    "occlude.roots.current=r1",
    "occlude.roots.secrets.r1=" + base64(32)
  };

  private static String base64(int bytes) {
    byte[] random = new byte[bytes];
    new SecureRandom().nextBytes(random);
    return Base64.getEncoder().encodeToString(random);
  }

  @Configuration(proxyBeanMethods = false)
  static class AnApplication {

    @Bean
    Axes axes() {
      return Axes.of(TENANT);
    }

    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }
  }

  @Test
  @DisplayName("contributes storage, and the charter is bound to it")
  void contributes_storage_and_the_charter_is_bound() {
    runner
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JdbcStorage.class);
              Charter charter = context.getBean(Charter.class);
              assertThatThrownBy(() -> charter.erasure("probe", (label, ctx) -> true))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("has been bound");
            });
  }

  /**
   * Everything a store keeps is encrypted, so a store without keys is not built -- and an
   * application that put this module on its classpath hears why at startup rather than serving
   * requests that all refuse.
   */
  @Test
  @DisplayName("refuses to start without keys, and says how to supply them")
  void refuses_to_start_without_keys() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CharterAutoConfiguration.class, JdbcCharterAutoConfiguration.class))
        .withPropertyValues(
            "occlude.migrate=false",
            "occlude.roots.current=r1",
            "occlude.roots.secrets.r1=" + base64(32))
        .withUserConfiguration(AnApplication.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("occlude.keys.current")
                    .hasMessageContaining("DataKeyProvider"));
  }

  /**
   * A store used to be rooted in a published constant, which anyone who could write could forge.
   */
  @Test
  @DisplayName("refuses to start without a secret root")
  void refuses_to_start_without_a_root() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CharterAutoConfiguration.class, JdbcCharterAutoConfiguration.class))
        .withPropertyValues(
            "occlude.migrate=false",
            "occlude.keys.current=k1",
            "occlude.keys.keks.k1=" + base64(32))
        .withUserConfiguration(AnApplication.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("occlude.roots.current"));
  }

  @Test
  @DisplayName("refuses a current root it has no secret for")
  void refuses_a_current_root_without_a_secret() {
    runner
        .withPropertyValues("occlude.roots.current=r2")
        .withUserConfiguration(AnApplication.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("no secret by that name"));
  }

  /**
   * A secret mangled on its way into the environment should say which one, not just "bad input".
   */
  @Test
  @DisplayName("refuses a key that is not base64, naming which one")
  void refuses_a_key_that_is_not_base64() {
    runner
        .withPropertyValues("occlude.keys.keks.k1=not base64!")
        .withUserConfiguration(AnApplication.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("key 'k1' is not valid base64"));
  }

  /** A root is only as strong as its secret, so a short one stops the application starting. */
  @Test
  @DisplayName("refuses a root shorter than 32 bytes, naming it")
  void refuses_a_short_root() {
    runner
        .withPropertyValues("occlude.roots.secrets.r1=" + base64(16))
        .withUserConfiguration(AnApplication.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("the root 'r1' needs a secret of at least 32 bytes"));
  }

  @Test
  @DisplayName("refuses a signing algorithm it does not know, naming the ones it does")
  void refuses_an_unknown_mac() {
    runner
        .withPropertyValues("occlude.roots.mac=HMAC_MD5")
        .withUserConfiguration(AnApplication.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("HMAC_SHA512"));
  }

  /** A KMS replaces the configured keys entirely, and nothing here second-guesses it. */
  @Test
  @DisplayName("uses the application's own DataKeyProvider over configured keys")
  void uses_the_applications_own_keys() {
    DataKeyProvider kms =
        new JceDataKeyProvider(
            "kms", Map.of("kms", new SecretKeySpec(Base64.getDecoder().decode(base64(32)), "AES")));
    runner
        .withUserConfiguration(AnApplication.class)
        .withBean(DataKeyProvider.class, () -> kms)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(DataKeyProvider.class)).isSameAs(kms);
            });
  }

  /** An application that brought its own storage keeps it, and this module stays out of the way. */
  @Test
  @DisplayName("leaves storage the application supplied alone")
  void leaves_application_storage_alone() {
    runner
        .withUserConfiguration(AnApplication.class)
        .withBean(Storage.class, MemoryStorage::new)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Storage.class);
              assertThat(context).doesNotHaveBean(JdbcStorage.class);
            });
  }

  /**
   * Migration left on is the default, and only a real Postgres proves it happened: the schema is
   * Postgres-specific, so H2 cannot run it and every other test here turns it off.
   */
  @Test
  @DisplayName("migrates the schema by default, since nobody said not to")
  void migrates_the_schema_by_default() throws Exception {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CharterAutoConfiguration.class, JdbcCharterAutoConfiguration.class))
        .withPropertyValues(KEYS_AND_ROOT)
        .withUserConfiguration(AnApplicationOnPostgres.class)
        .run(context -> assertThat(context).hasNotFailed());

    PGSimpleDataSource dataSource = postgresDataSource();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT * FROM occlude_value")) {
      assertThat(rows.next()).isFalse();
    }
  }

  private static PGSimpleDataSource postgresDataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  @Configuration(proxyBeanMethods = false)
  static class AnApplicationOnPostgres {

    @Bean
    Axes axes() {
      return Axes.of(TENANT);
    }

    @Bean
    DataSource dataSource() {
      return postgresDataSource();
    }
  }
}
