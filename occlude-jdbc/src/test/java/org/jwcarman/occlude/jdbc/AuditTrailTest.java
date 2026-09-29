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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.Bindings;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.Reveal;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.StorageIntegrityException;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** The record read back for an investigation: decrypted, and never taken on trust. */
@Testcontainers
@DisplayName("Reading the trail back")
class AuditTrailTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("trail")
          .withUsername("trail")
          .withPassword("trail");

  private static final OccludedType<String> NOTE = OccludedType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axes AXES = Axes.of(TENANT);

  private DataSource dataSource;
  private JdbcStorage storage;
  private AuditTrail trail;
  private Occluded<String> held;
  private Instant start;

  @BeforeEach
  void setUp() throws SQLException {
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    dataSource = pg;
    execute("DROP TABLE IF EXISTS occlude_audit, occlude_lineage, occlude_value");
    storage =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(new JceDataKeyProvider("k1", Map.of("k1", TestKeys.aes256())))
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .storage(AXES);
    DefaultCharter charter = new DefaultCharter(AXES);
    Occlude<String> notes = charter.source("notes", NOTE, Label.of(TENANT, "acme"));
    Reveal<String> desk = charter.reveal("desk", Ceiling.of(TENANT, Constraint.any()), NOTE);
    Reveal<String> elsewhere =
        charter.reveal("elsewhere", Ceiling.of(TENANT, Constraint.atMost("globex")), NOTE);
    charter.bind(
        Bindings.of(storage)
            .withIdentity(() -> AccessContext.of(Map.of("tenant", "acme", "principal", "dana"))));
    start = Instant.now().minusSeconds(60);
    held = notes.occlude("hello");
    desk.reveal(held);
    elsewhere.reveal(held);
    trail = storage.trail();
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  @Test
  @DisplayName("says what happened to a value, with its label and who was asking")
  void says_what_happened_to_a_value() {
    List<RecordedLine> lines = trail.about(held.id());

    assertThat(lines)
        .extracting(recorded -> recorded.line().operation() + " " + recorded.line().outcome())
        .containsExactly("CONCEAL ALLOWED", "REVEAL ALLOWED", "REVEAL REFUSED");
    AuditRecord refused = lines.getLast().line();
    assertThat(refused.target()).contains("elsewhere");
    assertThat(refused.reason()).contains("ABOVE_CEILING");
    assertThat(refused.detail()).isPresent();
    assertThat(refused.label()).hasValueSatisfying(label -> assertThat(label).contains("acme"));
    assertThat(refused.context()).containsEntry("principal", "dana");
    assertThat(lines).extracting(RecordedLine::entryId).isSorted();
  }

  @Test
  @DisplayName("reads a window of time, and nothing outside it")
  void reads_a_window_of_time() {
    assertThat(trail.between(start, Instant.now().plusSeconds(60))).hasSize(3);
    assertThat(trail.between(start.minusSeconds(3600), start)).isEmpty();
  }

  @Test
  @DisplayName("walks the whole trail a page at a time")
  void walks_the_whole_trail_a_page_at_a_time() {
    List<RecordedLine> walked = new ArrayList<>();
    List<RecordedLine> page = trail.after(0, 2);
    while (!page.isEmpty()) {
      walked.addAll(page);
      page = trail.after(page.getLast().entryId(), 2);
    }

    assertThat(walked).hasSize(3);
    assertThat(walked).extracting(RecordedLine::entryId).doesNotHaveDuplicates().isSorted();
  }

  @Test
  @DisplayName("refuses a line whose clear-text facts somebody changed")
  void refuses_changed_facts() throws SQLException {
    execute("UPDATE occlude_audit SET outcome = 'ALLOWED' WHERE outcome = 'REFUSED'");

    assertThatThrownBy(() -> trail.about(held.id()))
        .isInstanceOf(StorageIntegrityException.class)
        .hasMessageContaining("of the trail");
  }

  @Test
  @DisplayName("refuses a line signed with an algorithm it does not know")
  void refuses_an_unknown_algorithm() throws SQLException {
    execute("UPDATE occlude_audit SET mac = 'HmacMD5'");

    assertThatThrownBy(() -> trail.after(0, 10)).isInstanceOf(StorageIntegrityException.class);
  }

  /** Its label is the same as every other line's here, so the detail is what can differ. */
  @Test
  @DisplayName("refuses a line whose encrypted fields are not what was committed to")
  void refuses_changed_fields() throws SQLException {
    execute(
        "UPDATE occlude_audit SET detail = NULL"
            + " WHERE entry_id = (SELECT max(entry_id) FROM occlude_audit)");

    assertThatThrownBy(() -> trail.after(0, 10)).isInstanceOf(StorageIntegrityException.class);
  }

  @Test
  @DisplayName("reports a database it cannot reach as an outage")
  void reports_an_unreachable_database() {
    DataSource down =
        new PGSimpleDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            throw new SQLException("the database is down");
          }
        };
    AuditTrail blind =
        new JdbcStorageConfig()
            .dataSource(down)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(new JceDataKeyProvider("k1", Map.of("k1", TestKeys.aes256())))
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .withoutMigration()
            .storage(AXES)
            .trail();

    assertThatThrownBy(() -> blind.about("occ_anything"))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read the trail back");
  }

  @Test
  @DisplayName("refuses questions it cannot answer")
  void refuses_unanswerable_questions() {
    Instant now = Instant.now();

    assertThatThrownBy(() -> trail.after(0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> trail.about(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> trail.between(null, now)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> trail.between(now, null)).isInstanceOf(NullPointerException.class);
  }
}
