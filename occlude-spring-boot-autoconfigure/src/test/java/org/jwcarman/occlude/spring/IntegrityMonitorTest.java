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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
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
import org.jwcarman.occlude.jdbc.JdbcStorage;
import org.jwcarman.occlude.jdbc.JdbcStorageConfig;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Label;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** A store that checks itself, and says what it found without saying which rows. */
@Testcontainers
@DisplayName("Checking the store on a schedule")
class IntegrityMonitorTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axes AXES = Axes.of(TENANT);

  private final List<String> results = new ArrayList<>();
  private final ObservationRegistry observations = ObservationRegistry.create();
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
  private final Logger monitorLog = (Logger) LoggerFactory.getLogger(IntegrityMonitor.class);
  private DataSource dataSource;
  private Occluded<String> held;
  private IntegrityMonitor monitor;

  @BeforeEach
  void setUp() throws SQLException {
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                results.add(context.getLowCardinalityKeyValue(IntegrityMonitor.RESULT).getValue());
              }
            });
    logged.start();
    monitorLog.addAppender(logged);
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    dataSource = pg;
    execute("DROP TABLE IF EXISTS occlude_audit, occlude_lineage, occlude_value");
    JdbcStorage storage = store(dataSource).storage(AXES);
    DefaultCharter charter = new DefaultCharter(AXES);
    Occlude<String> notes =
        charter.source("notes", OccludedType.of(String.class), Label.of(TENANT, "acme"));
    charter.bind(Bindings.of(storage).withoutIdentity());
    held = notes.occlude("hello");
    monitor = new IntegrityMonitor(storage.integrity(), Duration.ofHours(1), observations);
  }

  @AfterEach
  void detach() {
    monitorLog.detachAppender(logged);
  }

  private static JdbcStorageConfig store(DataSource dataSource) {
    return new JdbcStorageConfig()
        .dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .encryptedWith(
            new JceDataKeyProvider("k1", Map.of("k1", new SecretKeySpec(random(), "AES"))))
        .rootedIn("r1", random());
  }

  private static byte[] random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return bytes;
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

  private List<String> messagesAt(Level level) {
    return logged.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @Test
  @DisplayName("finds an untouched store intact, and logs the head to anchor")
  void finds_an_untouched_store_intact() {
    monitor.check();

    assertThat(results).containsExactly("intact");
    assertThat(messagesAt(Level.INFO)).anyMatch(message -> message.contains("occlude trail head"));
    assertThat(messagesAt(Level.ERROR)).isEmpty();
  }

  @Test
  @DisplayName("reports an altered store at error, by count and never by id")
  void reports_an_altered_store() throws SQLException {
    execute("UPDATE occlude_value SET value_type = 'forged' WHERE value_id = ?", held.id());

    monitor.check();

    assertThat(results).containsExactly("altered");
    assertThat(messagesAt(Level.ERROR))
        .singleElement()
        .satisfies(message -> assertThat(message).contains("1 value(s) not as signed"))
        .satisfies(message -> assertThat(message).doesNotContain(held.id()));
  }

  @Test
  @DisplayName("reports what would not decrypt as unreadable, apart from altered")
  void reports_an_unreadable_store() throws SQLException {
    execute(
        "UPDATE occlude_value SET payload = set_byte(payload, 40, get_byte(payload, 40) # 1)"
            + " WHERE value_id = ?",
        held.id());

    monitor.check();

    assertThat(results).containsExactly("unreadable");
    assertThat(messagesAt(Level.WARN)).anyMatch(message -> message.contains("could not decrypt 1"));
  }

  @Test
  @DisplayName("records a run that could not finish as failed, and concludes nothing from it")
  void records_a_failed_run() {
    DataSource down =
        new PGSimpleDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            throw new SQLException("the database is down");
          }
        };
    IntegrityMonitor blind =
        new IntegrityMonitor(
            store(down).withoutMigration().storage(AXES).integrity(),
            Duration.ofHours(1),
            observations);

    blind.check();

    assertThat(results).containsExactly("failed");
    assertThat(messagesAt(Level.WARN)).anyMatch(message -> message.contains("could not finish"));
  }

  @Test
  @DisplayName("runs only between being started and stopped")
  void runs_between_start_and_stop() {
    assertThat(monitor.isRunning()).isFalse();
    monitor.start();
    assertThat(monitor.isRunning()).isTrue();
    monitor.stop();
    assertThat(monitor.isRunning()).isFalse();
  }
}
