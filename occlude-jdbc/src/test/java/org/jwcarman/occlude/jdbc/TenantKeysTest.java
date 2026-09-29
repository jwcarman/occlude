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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.crypto.DataKeyProvider;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.occlude.Bindings;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Fold;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.StorageUnreadableException;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each tenant's values under that tenant's own keys: destroy one tenant's key and exactly their
 * values stop being readable -- nobody else's, and never the record.
 */
@Testcontainers
@DisplayName("Keys per tenant")
class TenantKeysTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("tenants")
          .withUsername("tenants")
          .withPassword("tenants");

  enum Sensitivity {
    ORDINARY,
    CARDHOLDER
  }

  private static final OccludedType<String> NOTE = OccludedType.of(String.class);
  private static final TypeRef<String> TEXT = TypeRef.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Sensitivity> SENSITIVITY =
      Axis.ladder("sensitivity", Sensitivity.ORDINARY, Sensitivity.CARDHOLDER);
  private static final Axes AXES = Axes.of(TENANT, SENSITIVITY);

  private final SecretKey shared = TestKeys.aes256();
  private final SecretKey acme = TestKeys.aes256();
  private final SecretKey globex = TestKeys.aes256();
  private DataSource dataSource;
  private Occluded<String> acmes;
  private Occluded<String> globexes;
  private Occluded<String> mixed;

  @BeforeEach
  void setUp() throws SQLException {
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    dataSource = pg;
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS occlude_audit, occlude_lineage, occlude_value");
    }
    DefaultCharter charter = new DefaultCharter(AXES);
    Occlude<String> acmeNotes = charter.source("acme-notes", NOTE, Label.of(TENANT, "acme"));
    Occlude<String> globexNotes = charter.source("globex-notes", NOTE, Label.of(TENANT, "globex"));
    Fold<String, String> join =
        charter.fold(
            "join",
            NOTE,
            NOTE,
            all -> String.join(" ", all),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
    charter.bind(Bindings.of(store(this::tenantKeys)).withoutIdentity());
    acmes = acmeNotes.occlude("acme's");
    globexes = globexNotes.occlude("globex's");
    mixed = join.fold(List.of(acmes, globexes)).orThrow();
  }

  private DataKeyProvider tenantKeys(String tenant) {
    return switch (tenant) {
      case "acme" -> new JceDataKeyProvider("acme-1", Map.of("acme-1", acme));
      case "globex" -> new JceDataKeyProvider("globex-1", Map.of("globex-1", globex));
      default -> null;
    };
  }

  private JdbcStorage store(Function<String, DataKeyProvider> tenants) {
    return config().keyedBy(TENANT, tenants).storage(AXES);
  }

  private JdbcStorageConfig config() {
    return new JdbcStorageConfig()
        .dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .encryptedWith(new JceDataKeyProvider("shared-1", Map.of("shared-1", shared)))
        .rootedIn(TestKeys.ROOT_ID, TestKeys.root());
  }

  @Test
  @DisplayName("reads every tenant's values back under their own keys")
  void reads_every_tenants_values_back() {
    JdbcStorage storage = store(this::tenantKeys);

    assertThat(storage.value(acmes.id(), TEXT)).contains("acme's");
    assertThat(storage.value(globexes.id(), TEXT)).contains("globex's");
    assertThat(storage.integrity().check().intact()).isTrue();
  }

  /** Offboarding: acme's key is gone, and so is everything it protected -- and nothing else. */
  @Test
  @DisplayName("loses exactly one tenant's values when that tenant's key is destroyed")
  void loses_exactly_one_tenant_when_its_key_is_destroyed() {
    JdbcStorage shredded =
        store(
            tenant ->
                "acme".equals(tenant)
                    ? new JceDataKeyProvider("acme-2", Map.of("acme-2", TestKeys.aes256()))
                    : tenantKeys(tenant));
    String acmeId = acmes.id();

    assertThatThrownBy(() -> shredded.value(acmeId, TEXT))
        .isInstanceOf(StorageUnreadableException.class);
    assertThat(shredded.value(globexes.id(), TEXT)).contains("globex's");
    assertThat(shredded.metadata(acmes.id())).isPresent();
    assertThat(shredded.trail().about(acmes.id())).isNotEmpty();
    IntegrityReport report = shredded.integrity().check();
    assertThat(report.intact()).isTrue();
    assertThat(report.sweep().unreadableValues()).containsExactly(acmes.id());
  }

  /** A value made from two tenants' data belongs to neither, so it is under the shared keys. */
  @Test
  @DisplayName("keeps a value mixing tenants under the shared keys")
  void keeps_a_mixture_under_the_shared_keys() {
    JdbcStorage neitherTenant =
        store(tenant -> new JceDataKeyProvider("gone", Map.of("gone", TestKeys.aes256())));

    assertThat(neitherTenant.value(mixed.id(), TEXT)).contains("acme's globex's");
  }

  @Test
  @DisplayName("moves a tenant onto their next key when re-encrypting")
  void moves_a_tenant_onto_their_next_key() {
    SecretKey next = TestKeys.aes256();
    store(
            tenant ->
                "acme".equals(tenant)
                    ? new JceDataKeyProvider("acme-2", Map.of("acme-1", acme, "acme-2", next))
                    : tenantKeys(tenant))
        .reencrypt();

    JdbcStorage onlyTheNextKey =
        store(
            tenant ->
                "acme".equals(tenant)
                    ? new JceDataKeyProvider("acme-2", Map.of("acme-2", next))
                    : tenantKeys(tenant));
    assertThat(onlyTheNextKey.value(acmes.id(), TEXT)).contains("acme's");
  }

  @Test
  @DisplayName("keys by a rung of a ladder, too")
  void keys_by_a_rung_of_a_ladder() {
    SecretKey ordinary = TestKeys.aes256();
    JdbcStorage storage =
        config()
            .keyedBy(SENSITIVITY, rung -> new JceDataKeyProvider(rung, Map.of(rung, ordinary)))
            .storage(AXES);
    DefaultCharter charter = new DefaultCharter(AXES);
    Occlude<String> notes = charter.source("notes", NOTE, Label.of(TENANT, "acme"));
    charter.bind(Bindings.of(storage).withoutIdentity());

    Occluded<String> held = notes.occlude("ordinary");

    assertThat(storage.value(held.id(), TEXT)).contains("ordinary");
  }

  @Test
  @DisplayName("refuses to write for a tenant nothing supplies keys for")
  void refuses_a_tenant_without_keys() {
    JdbcStorage storage = store(tenant -> null);
    DefaultCharter charter = new DefaultCharter(AXES);
    Occlude<String> notes = charter.source("notes", NOTE, Label.of(TENANT, "initech"));
    charter.bind(Bindings.of(storage).withoutIdentity());

    assertThatThrownBy(() -> notes.occlude("initech's"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nothing supplies keys")
        .hasMessageNotContaining("initech");
  }

  /**
   * An offboarded tenant whose keys are simply gone reads as unreadable, and stops nothing else.
   */
  @Test
  @DisplayName("treats a tenant nothing supplies keys for any more as unreadable, not as a failure")
  void treats_a_tenant_without_keys_as_unreadable() {
    JdbcStorage offboarded = store(tenant -> "acme".equals(tenant) ? null : tenantKeys(tenant));
    String acmeId = acmes.id();

    assertThatThrownBy(() -> offboarded.value(acmeId, TEXT))
        .isInstanceOf(StorageUnreadableException.class)
        .hasMessageNotContaining("acme'");
    assertThat(offboarded.value(globexes.id(), TEXT)).contains("globex's");
    assertThat(offboarded.sweep().unreadableValues()).containsExactly(acmes.id());
  }

  @Test
  @DisplayName("refuses an axis the charter does not declare")
  void refuses_an_undeclared_axis() {
    JdbcStorageConfig keyed = config().keyedBy(Axis.matching("region"), this::tenantKeys);

    assertThatThrownBy(() -> keyed.storage(AXES))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'region'");
  }
}
