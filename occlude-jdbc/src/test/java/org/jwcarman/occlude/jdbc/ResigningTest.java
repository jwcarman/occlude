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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.SecretKey;
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
import org.jwcarman.occlude.Fold;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.Reveal;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.StorageIntegrityException;
import org.jwcarman.occlude.storage.StorageUnreadableException;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Retiring a root: everything signed again under the new one, and nothing laundered on the way. */
@Testcontainers
@DisplayName("Re-signing under a new root")
class ResigningTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("resigning")
          .withUsername("resigning")
          .withPassword("resigning");

  private static final OccludedType<String> NOTE = OccludedType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axes AXES = Axes.of(TENANT);
  private static final byte[] OLD = "the root this store is retiring, 32+ bytes".getBytes(UTF_8);
  private static final byte[] NEW = "the root this store is moving to, 32+ bytes".getBytes(UTF_8);
  private static final Function<String, byte[]> BOTH =
      id ->
          switch (id) {
            case "old" -> OLD.clone();
            case "new" -> NEW.clone();
            default -> null;
          };
  private static final Function<String, byte[]> ONLY_NEW =
      id -> "new".equals(id) ? NEW.clone() : null;

  private final SecretKey key = TestKeys.aes256();
  private DataSource dataSource;
  private Occluded<String> note;
  private Occluded<String> shouted;
  private Occluded<String> joined;

  @BeforeEach
  void setUp() throws SQLException {
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    dataSource = pg;
    execute("DROP TABLE IF EXISTS occlude_audit, occlude_lineage, occlude_value");
    Portals old = portals(store("old", BOTH, MacAlgorithm.HMAC_SHA256));
    note = old.notes.occlude("hello");
    shouted = old.shout.derive(note).orThrow();
    joined = old.join.fold(List.of(note, shouted)).orThrow();
    old.desk.reveal(joined);
  }

  private JdbcStorage store(String current, Function<String, byte[]> roots, MacAlgorithm mac) {
    return new JdbcStorageConfig()
        .dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .encryptedWith(new JceDataKeyProvider("k1", Map.of("k1", key)))
        .rootedIn(current, roots)
        .signedWith(mac)
        .storage(AXES);
  }

  private record Portals(
      Occlude<String> notes,
      Derivation<String, String> shout,
      Fold<String, String> join,
      Reveal<String> desk) {}

  private static Portals portals(JdbcStorage storage) {
    DefaultCharter charter = new DefaultCharter(AXES);
    Ceiling anything = Ceiling.of(TENANT, Constraint.any());
    Portals portals =
        new Portals(
            charter.source("notes", NOTE, Label.of(TENANT, "acme")),
            charter.derivation(
                "shout", NOTE, NOTE, String::toUpperCase, d -> d.accepting(anything)),
            charter.fold(
                "join", NOTE, NOTE, all -> String.join(" ", all), d -> d.accepting(anything)),
            charter.reveal("desk", anything, NOTE));
    charter.bind(Bindings.of(storage).withIdentity(() -> AccessContext.of("tenant", "acme")));
    return portals;
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

  private long rowsUnder(String root) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT (SELECT count(*) FROM occlude_value WHERE root_id = ?)"
                    + " + (SELECT count(*) FROM occlude_audit WHERE root_id = ?)")) {
      statement.setString(1, root);
      statement.setString(2, root);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  @Test
  @DisplayName("re-signs everything, so the old root can go and every read still verifies")
  void resigns_everything_so_the_old_root_can_go() throws SQLException {
    long before = rowsUnder("old");

    Resigned resigned = store("new", BOTH, MacAlgorithm.HMAC_SHA256).integrity().resign();

    assertThat(resigned.values()).isEqualTo(3);
    assertThat(resigned.values() + resigned.lines()).isEqualTo(before);
    assertThat(rowsUnder("old")).isZero();
    JdbcStorage retired = store("new", ONLY_NEW, MacAlgorithm.HMAC_SHA256);
    assertThat(retired.integrity().check().intact()).isTrue();
    assertThat(portals(retired).desk.reveal(joined).value()).contains("hello HELLO");
  }

  /** The lines an anchor named now carry new digests, so the old one stops holding. */
  @Test
  @DisplayName("hands back the head before and after, and only the new one holds")
  void hands_back_both_heads() {
    JdbcStorage storage = store("new", BOTH, MacAlgorithm.HMAC_SHA256);
    TrailHead published = storage.head().orElseThrow();

    Resigned resigned = storage.resign();

    assertThat(resigned.before()).contains(published);
    assertThat(storage.stillHolds(published)).isFalse();
    assertThat(storage.stillHolds(resigned.after().orElseThrow())).isTrue();
  }

  @Test
  @DisplayName(
      "re-signs what was derived from an old value, even when it was signed under the new root")
  void resigns_what_was_derived_from_an_old_value() throws SQLException {
    JdbcStorage storage = store("new", BOTH, MacAlgorithm.HMAC_SHA256);
    Occluded<String> newer = portals(storage).shout.derive(joined).orThrow();

    storage.resign();

    assertThat(rowsUnder("old")).isZero();
    assertThat(store("new", ONLY_NEW, MacAlgorithm.HMAC_SHA256).integrity().check().intact())
        .isTrue();
    assertThat(storage.contains(newer.id())).isTrue();
  }

  @Test
  @DisplayName("re-signs everything when only the algorithm changes")
  void resigns_when_only_the_algorithm_changes() {
    JdbcStorage storage = store("old", BOTH, MacAlgorithm.HMAC_SHA512);

    Resigned resigned = storage.resign();

    assertThat(resigned.values()).isEqualTo(3);
    assertThat(storage.integrity().check().intact()).isTrue();
  }

  @Test
  @DisplayName("re-signs an empty store, which has no head either side")
  void resigns_an_empty_store() throws SQLException {
    execute("DELETE FROM occlude_lineage");
    execute("DELETE FROM occlude_value");
    execute("DELETE FROM occlude_audit");

    Resigned resigned = store("new", BOTH, MacAlgorithm.HMAC_SHA256).resign();

    assertThat(resigned.values()).isZero();
    assertThat(resigned.before()).isEmpty();
    assertThat(resigned.after()).isEmpty();
  }

  /** More lines than one page, so the walk has to carry the chain from page to page. */
  @Test
  @DisplayName("carries the chain across pages of a long trail")
  void carries_the_chain_across_pages() {
    JdbcStorage old = store("old", BOTH, MacAlgorithm.HMAC_SHA256);
    Reveal<String> desk = portals(old).desk();
    for (int i = 0; i < 520; i++) {
      desk.reveal(note);
    }
    JdbcStorage storage = store("new", BOTH, MacAlgorithm.HMAC_SHA256);

    Resigned resigned = storage.resign();

    assertThat(resigned.lines()).isGreaterThan(520);
    assertThat(store("new", ONLY_NEW, MacAlgorithm.HMAC_SHA256).integrity().check().intact())
        .isTrue();
  }

  /** Lines already under the current root before the first old one are left exactly as they are. */
  @Test
  @DisplayName("re-signs from the first old line, leaving the lines before it alone")
  void resigns_from_the_first_old_line() throws SQLException {
    execute("DELETE FROM occlude_lineage");
    execute("DELETE FROM occlude_value");
    execute("DELETE FROM occlude_audit");
    portals(store("new", BOTH, MacAlgorithm.HMAC_SHA256))
        .notes()
        .occlude("first, under the new root");
    long untouched = rowsUnder("new");
    portals(store("old", BOTH, MacAlgorithm.HMAC_SHA256)).notes().occlude("then under the old one");

    Resigned resigned = store("new", BOTH, MacAlgorithm.HMAC_SHA256).resign();

    assertThat(resigned.lines()).isEqualTo(1);
    assertThat(rowsUnder("new")).isEqualTo(untouched + 2);
    assertThat(store("new", ONLY_NEW, MacAlgorithm.HMAC_SHA256).integrity().check().intact())
        .isTrue();
  }

  @Test
  @DisplayName("does nothing the second time")
  void does_nothing_the_second_time() {
    JdbcStorage storage = store("new", BOTH, MacAlgorithm.HMAC_SHA256);
    storage.resign();

    Resigned again = storage.resign();

    assertThat(again.values()).isZero();
    assertThat(again.lines()).isZero();
  }

  @Test
  @DisplayName("refuses a value somebody altered, and re-signs nothing")
  void refuses_an_altered_value() throws SQLException {
    long before = rowsUnder("old");
    execute("UPDATE occlude_value SET derivation = 'forged' WHERE value_id = ?", shouted.id());

    assertThatThrownBy(() -> store("new", BOTH, MacAlgorithm.HMAC_SHA256).resign())
        .isInstanceOf(StorageIntegrityException.class)
        .hasMessageContaining(shouted.id());
    assertThat(rowsUnder("old")).isEqualTo(before);
  }

  @Test
  @DisplayName("refuses a line somebody altered, and re-signs nothing")
  void refuses_an_altered_line() throws SQLException {
    long before = rowsUnder("old");
    execute("UPDATE occlude_audit SET outcome = 'REFUSED' WHERE operation = 'REVEAL'");

    assertThatThrownBy(() -> store("new", BOTH, MacAlgorithm.HMAC_SHA256).resign())
        .isInstanceOf(StorageIntegrityException.class)
        .hasMessageContaining("of the trail");
    assertThat(rowsUnder("old")).isEqualTo(before);
  }

  @Test
  @DisplayName("refuses a line whose predecessor is not the one it was signed after")
  void refuses_a_broken_chain() throws SQLException {
    execute(
        "UPDATE occlude_audit SET previous = NULL WHERE entry_id = (SELECT max(entry_id) FROM"
            + " occlude_audit)");

    assertThatThrownBy(() -> store("new", BOTH, MacAlgorithm.HMAC_SHA256).resign())
        .isInstanceOf(StorageIntegrityException.class);
  }

  @Test
  @DisplayName("refuses a value whose parent is not stored")
  void refuses_a_missing_parent() throws SQLException {
    execute(
        "INSERT INTO occlude_lineage (child_id, parent_id, position) VALUES (?, 'occ_missing', 9)",
        shouted.id());

    assertThatThrownBy(() -> store("new", BOTH, MacAlgorithm.HMAC_SHA256).resign())
        .isInstanceOf(StorageIntegrityException.class);
  }

  @Test
  @DisplayName("refuses a lineage somebody forged into a cycle")
  void refuses_a_cycle() throws SQLException {
    execute(
        "INSERT INTO occlude_lineage (child_id, parent_id, position) VALUES (?, ?, 5)",
        note.id(),
        shouted.id());

    assertThatThrownBy(() -> store("new", BOTH, MacAlgorithm.HMAC_SHA256).resign())
        .isInstanceOf(StorageIntegrityException.class)
        .hasMessageContaining("cycle");
  }

  /** A commitment to plaintext cannot be made again without the plaintext. */
  @Test
  @DisplayName("stops, and re-signs nothing, when a field will not decrypt")
  void stops_when_a_field_will_not_decrypt() throws SQLException {
    long before = rowsUnder("old");
    JdbcStorage withoutTheKey =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(new JceDataKeyProvider("k2", Map.of("k2", TestKeys.aes256())))
            .rootedIn("new", BOTH)
            .storage(AXES);

    assertThatThrownBy(withoutTheKey::resign).isInstanceOf(StorageUnreadableException.class);
    assertThat(rowsUnder("old")).isEqualTo(before);
  }
}
