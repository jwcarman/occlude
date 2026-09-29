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
import static org.assertj.core.api.Assertions.tuple;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.Bindings;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Derivation;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The integrity check on a store bigger than it can hold at once.
 *
 * <p>The check reads every row a store has, so how it reads them is what decides whether a large
 * store can run it. Two ways it could not: every scan was read into memory entire -- the sweep
 * reads every ciphertext -- and the value graph asked for each value's parents with a query of its
 * own. These pin both down by watching what the check asks the database, on a store with more rows
 * than one fetch holds.
 */
@Testcontainers
@DisplayName("The integrity check at scale")
class IntegrityAtScaleTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("scale")
          .withUsername("scale")
          .withPassword("scale");

  record Note(String text) {}

  private static final OccludedType<Note> NOTE = OccludedType.of(Note.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axes AXES = Axes.of(TENANT);

  /** More values than one fetch holds, so every scan crosses a page. */
  private static final int NOTES = Verification.SCAN_FETCH + 50;

  /**
   * What the check asked the database: the statement, its fetch size, and whether in a transaction.
   */
  record Asked(String sql, int fetchSize, boolean autoCommit) {

    boolean scansAWholeTable() {
      String flat = sql.replaceAll("\\s+", " ");
      return flat.contains("FROM occlude_value v ORDER BY")
          || flat.contains("FROM occlude_audit ORDER BY entry_id")
              && !flat.contains("DESC")
              && !flat.contains("LIMIT");
    }
  }

  private final List<Asked> asked = new CopyOnWriteArrayList<>();
  private DataSource dataSource;
  private JdbcStorage storage;
  private final List<Occluded<Note>> stored = new ArrayList<>();

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
    storage =
        new JdbcStorageConfig()
            .dataSource(recording(dataSource))
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(new JceDataKeyProvider("k1", Map.of("k1", TestKeys.aes256())))
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .storage(AXES);
    DefaultCharter charter = new DefaultCharter(AXES);
    Occlude<Note> notes = charter.source("notes", NOTE, Label.of(TENANT, "acme"));
    Derivation<Note, Note> shout =
        charter.derivation(
            "shout",
            NOTE,
            NOTE,
            note -> new Note(note.text().toUpperCase()),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
    charter.bind(Bindings.of(storage).withIdentity(() -> AccessContext.of("tenant", "acme")));
    for (int i = 0; i < NOTES; i++) {
      Occluded<Note> note = notes.occlude(new Note("note " + i));
      stored.add(note);
      // Every tenth has a child, so the graph has edges to walk and not only roots.
      if (i % 10 == 0) {
        shout.derive(note).orThrow();
      }
    }
    asked.clear();
  }

  @Test
  @DisplayName("a store larger than one fetch checks intact")
  void a_large_store_checks_intact() {
    IntegrityReport report = storage.integrity().check();

    assertThat(report.intact()).isTrue();
    assertThat(report.unreadable()).isFalse();
  }

  @Test
  @DisplayName("tampering past the first fetch is still found")
  void tampering_past_the_first_fetch_is_found() throws SQLException {
    String late = stored.getLast().id();
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_value SET value_type = 'Other' WHERE value_id = ?")) {
      statement.setString(1, late);
      statement.executeUpdate();
    }

    IntegrityReport report = storage.integrity().check();

    assertThat(report.brokenValues()).containsExactly(late);
    assertThat(report.sweep().alteredValues()).containsExactly(late);
  }

  @Test
  @DisplayName("every whole-table scan streams, in a transaction with a fetch size")
  void every_scan_streams() {
    storage.integrity().check();

    List<Asked> scans = asked.stream().filter(Asked::scansAWholeTable).toList();
    // The trail walk, the value graph, and the sweep's two.
    assertThat(scans).hasSize(4);
    assertThat(scans)
        .extracting(Asked::fetchSize, Asked::autoCommit)
        .containsOnly(tuple(Verification.SCAN_FETCH, false));
  }

  @Test
  @DisplayName("the value graph is one query, however many values there are")
  void the_value_graph_is_one_query() {
    storage.brokenValues();

    assertThat(asked).hasSize(1);
  }

  /** The same data source, noting every query a connection from it runs. */
  private DataSource recording(DataSource target) {
    return (DataSource)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoked(method, target, args);
              return result instanceof Connection connection ? recording(connection) : result;
            });
  }

  private Connection recording(Connection target) {
    return (Connection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = invoked(method, target, args);
              return method.getName().equals("prepareStatement")
                      && result instanceof PreparedStatement statement
                  ? recording(statement, (String) args[0], target)
                  : result;
            });
  }

  private PreparedStatement recording(PreparedStatement target, String sql, Connection connection) {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
              if (method.getName().equals("executeQuery")) {
                asked.add(new Asked(sql, target.getFetchSize(), connection.getAutoCommit()));
              }
              return invoked(method, target, args);
            });
  }

  private static Object invoked(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
