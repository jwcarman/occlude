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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.crypto.DataKeyProvider;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Derivation;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.Reveal;
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
 * Changing keys and algorithms without rewriting what is signed.
 *
 * <p>The record and the value graph are signed over keyed commitments to what things say, never
 * over their ciphertext. So encryption can be redone -- to retire a key -- and a key can be
 * destroyed, and everything still verifies; and the MAC each row was signed with is recorded, so
 * moving to another is a root rotation rather than a rewrite.
 */
@Testcontainers
@DisplayName("Changing keys and algorithms")
class CryptoAgilityTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("agility")
          .withUsername("agility")
          .withPassword("agility");

  record Note(String text) {}

  private static final OccludedType<Note> NOTE = OccludedType.of(Note.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axes AXES = Axes.of(TENANT);
  private static final TypeRef<Note> NOTE_TYPE = TypeRef.of(Note.class);

  private final SecretKey first = TestKeys.aes256();
  private final SecretKey second = TestKeys.aes256();
  private DataSource dataSource;

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
  }

  private JdbcStorage store(DataKeyProvider keys, MacAlgorithm mac) {
    return new JdbcStorageConfig()
        .dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .encryptedWith(keys)
        .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
        .signedWith(mac)
        .storage(AXES);
  }

  private JdbcStorage underFirst() {
    return store(new JceDataKeyProvider("k1", Map.of("k1", first)), MacAlgorithm.HMAC_SHA256);
  }

  private JdbcStorage underSecondOnly() {
    return store(new JceDataKeyProvider("k2", Map.of("k2", second)), MacAlgorithm.HMAC_SHA256);
  }

  /** Occludes a note, derives from it and reveals it: values, lineage and every kind of line. */
  private List<Occluded<Note>> writeThrough(JdbcStorage storage) {
    DefaultCharter charter = new DefaultCharter(AXES);
    Ceiling anything = Ceiling.of(TENANT, Constraint.any());
    Occlude<Note> notes = charter.source("notes", NOTE, Label.of(TENANT, "acme"));
    Derivation<Note, Note> shout =
        charter.derivation(
            "shout",
            NOTE,
            NOTE,
            note -> new Note(note.text().toUpperCase()),
            d -> d.accepting(anything));
    Reveal<Note> desk = charter.sink("desk", anything, NOTE).reading(NOTE);
    charter.bind(storage, () -> AccessContext.of("tenant", "acme"));

    Occluded<Note> note = notes.occlude(new Note("hello"));
    Occluded<Note> shouted = shout.derive(note).orThrow();
    desk.reveal(shouted);
    List<Occluded<Note>> written = new ArrayList<>();
    written.add(note);
    written.add(shouted);
    return written;
  }

  private static void assertIntact(JdbcStorage storage) {
    assertThat(storage.firstBrokenEntry()).isEmpty();
    assertThat(storage.brokenValues()).isEmpty();
    assertThat(storage.missingValues()).isEmpty();
  }

  @Test
  @DisplayName("retires a key once everything has been re-encrypted under the next one")
  void retires_a_key_after_reencrypting() {
    List<Occluded<Note>> written = writeThrough(underFirst());
    String id = written.getFirst().id();
    JdbcStorage withoutTheOldKey = underSecondOnly();
    assertThatThrownBy(() -> withoutTheOldKey.value(id, NOTE_TYPE))
        .as("before re-encrypting, the old key is still needed")
        .isInstanceOf(RuntimeException.class);

    JdbcStorage rotating =
        store(
            new JceDataKeyProvider("k2", Map.of("k1", first, "k2", second)),
            MacAlgorithm.HMAC_SHA256);
    assertThat(rotating.reencrypt()).isPositive();

    JdbcStorage retired = underSecondOnly();
    assertThat(retired.value(id, NOTE_TYPE)).contains(new Note("hello"));
    assertThat(retired.metadata(written.get(1).id())).isPresent();
    assertIntact(retired);
  }

  /** Encrypting a swapped ciphertext afresh would bless it as something this store wrote. */
  @Test
  @DisplayName("refuses to re-encrypt a field that is not what was signed for it")
  void refuses_to_launder_a_tampered_field() throws SQLException {
    List<Occluded<Note>> written = writeThrough(underFirst());
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_value SET payload = (SELECT payload FROM occlude_value WHERE"
                    + " value_id = ?) WHERE value_id = ?")) {
      statement.setString(1, written.get(1).id());
      statement.setString(2, written.getFirst().id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    JdbcStorage rotating =
        store(
            new JceDataKeyProvider("k2", Map.of("k1", first, "k2", second)),
            MacAlgorithm.HMAC_SHA256);

    assertThatThrownBy(rotating::reencrypt)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "the payload stored for " + written.getFirst().id() + " is not what was signed for it");
  }

  /** A page is five hundred rows; a store bigger than one must come out entirely re-encrypted. */
  @Test
  @DisplayName("re-encrypts a store larger than one page, all of it")
  void reencrypts_across_pages() {
    JdbcStorage storage = underFirst();
    DefaultCharter charter = new DefaultCharter(AXES);
    Occlude<Note> notes = charter.source("notes", NOTE, Label.of(TENANT, "acme"));
    charter.bind(storage, () -> AccessContext.of("tenant", "acme"));
    List<Occluded<Note>> written = new ArrayList<>();
    for (int i = 0; i < 520; i++) {
      written.add(notes.occlude(new Note("note " + i)));
    }

    JdbcStorage rotating =
        store(
            new JceDataKeyProvider("k2", Map.of("k1", first, "k2", second)),
            MacAlgorithm.HMAC_SHA256);
    // 520 values and the 520 lines that announced them.
    assertThat(rotating.reencrypt()).isEqualTo(1040);

    JdbcStorage retired = underSecondOnly();
    assertThat(retired.value(written.getLast().id(), NOTE_TYPE)).contains(new Note("note 519"));
    assertIntact(retired);
  }

  @Test
  @DisplayName("refuses to re-encrypt a line whose protected fields were swapped")
  void refuses_to_launder_a_tampered_line() throws SQLException {
    writeThrough(underFirst());
    long firstEntry = firstLine();
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_audit SET context = (SELECT context FROM occlude_audit WHERE"
                    + " entry_id = ?) WHERE entry_id = ?")) {
      statement.setLong(1, firstEntry + 1);
      statement.setLong(2, firstEntry);
      statement.executeUpdate();
    }
    // The context of every line here is the same access, so make the swap say something else.
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_audit SET label = NULL WHERE entry_id = ?")) {
      statement.setLong(1, firstEntry);
      statement.executeUpdate();
    }

    JdbcStorage rotating = underFirst();

    assertThatThrownBy(rotating::reencrypt)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("line " + firstEntry + " of the trail is not what was signed for it");
  }

  @Test
  @DisplayName("refuses to re-encrypt a line whose MAC name was rewritten")
  void refuses_to_reencrypt_a_line_with_a_rewritten_mac() throws SQLException {
    writeThrough(underFirst());
    long firstEntry = firstLine();
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_audit SET mac = 'HmacMD5' WHERE entry_id = ?")) {
      statement.setLong(1, firstEntry);
      statement.executeUpdate();
    }

    JdbcStorage rotating = underFirst();

    assertThatThrownBy(rotating::reencrypt).hasMessageContaining("line " + firstEntry);
  }

  /**
   * A read is checked too, so a rewritten MAC name cannot make a stored value
   * unverifiable-yet-served.
   */
  @Test
  @DisplayName("refuses to read a value whose MAC name was rewritten")
  void refuses_to_read_a_value_with_a_rewritten_mac() throws SQLException {
    List<Occluded<Note>> written = writeThrough(underFirst());
    String id = written.getFirst().id();
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_value SET mac = 'HmacMD5' WHERE value_id = ?")) {
      statement.setString(1, id);
      statement.executeUpdate();
    }

    JdbcStorage storage = underFirst();

    assertThatThrownBy(() -> storage.value(id, NOTE_TYPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not what was signed");
    assertThat(storage.brokenValues()).contains(id);
  }

  @Test
  @DisplayName("does not accept an anchor whose digest was made up")
  void does_not_accept_a_forged_anchor() {
    JdbcStorage storage = underFirst();
    writeThrough(storage);
    TrailHead real = storage.head().orElseThrow();

    TrailHead forged = new TrailHead(real.entryId(), new byte[] {1, 2, 3});

    assertThat(storage.stillHolds(real)).isTrue();
    assertThat(storage.stillHolds(forged)).isFalse();
  }

  private long firstLine() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT MIN(entry_id) FROM occlude_audit")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** Destroying a key erases what it protected, and must not take the record down with it. */
  @Test
  @DisplayName("still verifies after the key that encrypted everything is gone")
  void verifies_after_a_key_is_destroyed() {
    List<Occluded<Note>> written = writeThrough(underFirst());

    JdbcStorage keyless = underSecondOnly();

    assertIntact(keyless);
    String id = written.getFirst().id();
    assertThatThrownBy(() -> keyless.value(id, NOTE_TYPE)).isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName(
      "records what each row was signed with, and verifies rows signed with different ones")
  void records_the_mac_each_row_was_signed_with() throws SQLException {
    writeThrough(
        store(new JceDataKeyProvider("k1", Map.of("k1", first)), MacAlgorithm.HMAC_SHA512));
    assertThat(macs("occlude_value")).containsExactly("HmacSHA512");
    assertThat(macs("occlude_audit")).containsExactly("HmacSHA512");

    JdbcStorage later = underFirst();
    writeThrough(later);

    assertThat(macs("occlude_audit")).containsExactlyInAnyOrder("HmacSHA256", "HmacSHA512");
    assertIntact(later);
  }

  /** The name is read back from the database, so rewriting it must not change what is accepted. */
  @Test
  @DisplayName("treats a rewritten MAC name as a broken row, never as an instruction")
  void refuses_a_rewritten_mac_name() throws SQLException {
    List<Occluded<Note>> written = writeThrough(underFirst());
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE occlude_audit SET mac = 'HmacMD5' WHERE entry_id = (SELECT MIN(entry_id) FROM"
              + " occlude_audit)");
      statement.executeUpdate(
          "UPDATE occlude_value SET mac = 'HmacSHA384' WHERE value_id = '"
              + written.getFirst().id()
              + "'");
    }

    JdbcStorage storage = underFirst();

    assertThat(storage.firstBrokenEntry()).isPresent();
    assertThat(storage.brokenValues()).contains(written.getFirst().id(), written.get(1).id());
  }

  private List<String> macs(String table) throws SQLException {
    List<String> found = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT DISTINCT mac FROM " + table)) {
      while (rows.next()) {
        found.add(rows.getString("mac"));
      }
    }
    return found;
  }
}
