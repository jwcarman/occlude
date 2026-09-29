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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.occlude.Bindings;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Label;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every act commits on its own, so the store must never be handed the middle of somebody else's
 * transaction: committing it would commit their work early.
 */
@Testcontainers
@DisplayName("A connection handed over mid-transaction")
class SomebodyElsesTransactionTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("elsewhere")
          .withUsername("elsewhere")
          .withPassword("elsewhere");

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axes AXES = Axes.of(TENANT);

  private PGSimpleDataSource pg;
  private Connection shared;
  private JdbcStorage storage;
  private Occlude<String> notes;

  @BeforeEach
  void setUp() throws SQLException {
    pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    try (Connection connection = pg.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS occlude_audit, occlude_lineage, occlude_value, work");
      statement.execute("CREATE TABLE work (n INT)");
    }
    store(pg).storage(AXES);
    shared = pg.getConnection();
    shared.setAutoCommit(false);
    storage = store(always(shared)).withoutMigration().storage(AXES);
    DefaultCharter charter = new DefaultCharter(AXES);
    notes = charter.source("notes", OccludedType.of(String.class), Label.of(TENANT, "acme"));
    charter.bind(Bindings.of(storage).withoutIdentity());
  }

  @AfterEach
  void close() throws SQLException {
    shared.close();
  }

  private static JdbcStorageConfig store(DataSource dataSource) {
    return new JdbcStorageConfig()
        .dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .encryptedWith(new JceDataKeyProvider("k1", Map.of("k1", TestKeys.aes256())))
        .rootedIn(TestKeys.ROOT_ID, TestKeys.root());
  }

  /** A data source that always hands out the same connection, and never lets it be closed. */
  private static DataSource always(Connection connection) {
    Connection unclosable =
        (Connection)
            Proxy.newProxyInstance(
                SomebodyElsesTransactionTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) ->
                    method.getName().equals("close") ? null : invoked(method, connection, args));
    return (DataSource)
        Proxy.newProxyInstance(
            SomebodyElsesTransactionTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> unclosable);
  }

  private static Object invoked(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private long committed(String table) throws SQLException {
    try (Connection other = pg.getConnection();
        var statement = other.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  @Test
  @DisplayName("refuses it once that transaction has written, and commits none of its work")
  void refuses_a_transaction_that_has_written() throws SQLException {
    try (var statement = shared.createStatement()) {
      statement.execute("INSERT INTO work VALUES (1)");
    }

    assertThatThrownBy(() -> notes.occlude("hello"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("somebody else's transaction");

    assertThat(committed("work")).isZero();
    assertThat(committed("occlude_value")).isZero();
    shared.rollback();
  }

  /** A pool configured with auto-commit off is ordinary, and nothing is in progress yet. */
  @Test
  @DisplayName("works as usual from a pool that hands out connections with auto-commit off")
  void works_with_auto_commit_off() throws SQLException {
    Occluded<String> held = notes.occlude("hello");

    assertThat(storage.contains(held.id())).isTrue();
    assertThat(committed("occlude_value")).isEqualTo(1);
    assertThat(shared.getAutoCommit()).isFalse();
  }
}
