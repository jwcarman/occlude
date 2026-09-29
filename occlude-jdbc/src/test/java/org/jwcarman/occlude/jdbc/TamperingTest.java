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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Derivation;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.Reveal;
import org.jwcarman.occlude.StorageIntegrityException;
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
 * What somebody with write access to the tables can and cannot do.
 *
 * <p>Every test here edits the database directly, the way an attacker who got past the application
 * would, and asserts that the library notices: on read, in the record, or in a verifier.
 */
@Testcontainers
@DisplayName("Tampering with the tables")
class TamperingTest {

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("tampering")
          .withUsername("tampering")
          .withPassword("tampering");

  record Note(String text) {}

  record Memo(String text) {}

  private static final OccludedType<Note> NOTE = OccludedType.of(Note.class);
  private static final OccludedType<Memo> MEMO = OccludedType.of(Memo.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axes AXES = Axes.of(TENANT);

  private DataSource dataSource;
  private JdbcStorage storage;
  private Occlude<Note> notes;
  private Occlude<Memo> memos;
  private Derivation<Note, Note> shout;
  private Reveal<Note> noteDesk;
  private Reveal<Memo> memoDesk;

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
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(new JceDataKeyProvider("k1", Map.of("k1", TestKeys.aes256())))
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .storage(AXES);
    DefaultCharter charter = new DefaultCharter(AXES);
    Ceiling anything = Ceiling.of(TENANT, Constraint.any());
    notes = charter.source("notes", NOTE, Label.of(TENANT, "acme"));
    memos = charter.source("memos", MEMO, Label.of(TENANT, "acme"));
    shout =
        charter.derivation(
            "shout",
            NOTE,
            NOTE,
            note -> new Note(note.text().toUpperCase()),
            d -> d.accepting(anything));
    noteDesk = charter.sink("note-desk", anything, NOTE).reading(NOTE);
    memoDesk = charter.sink("memo-desk", anything, MEMO).reading(MEMO);
    charter.bind(storage, () -> AccessContext.of("tenant", "acme"));
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

  private List<String> reasonsRecorded() throws SQLException {
    List<String> reasons = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT reason FROM occlude_audit WHERE outcome = 'REFUSED'")) {
      while (rows.next()) {
        reasons.add(rows.getString("reason"));
      }
    }
    return reasons;
  }

  // ------------------------------------------------------------------ the value verifier

  /**
   * An unverifiable row used to compute an empty digest, which a blanked stored digest matched: the
   * verifier called it intact, and every row below it that was treated the same way.
   */
  @Test
  @DisplayName("does not call a row intact because its MAC name and digest were both blanked")
  void does_not_accept_a_blanked_digest_under_an_unknown_mac() throws SQLException {
    Occluded<Note> note = notes.occlude(new Note("hello"));

    execute("UPDATE occlude_value SET mac = 'x', digest = ''::bytea WHERE value_id = ?", note.id());

    assertThat(storage.brokenValues()).contains(note.id());
  }

  @Test
  @DisplayName("does not call a row intact because its root and digest were both blanked")
  void does_not_accept_a_blanked_digest_under_an_unknown_root() throws SQLException {
    Occluded<Note> note = notes.occlude(new Note("hello"));
    Occluded<Note> shouted = shout.derive(note).orThrow();

    execute("UPDATE occlude_value SET root_id = 'x', digest = ''::bytea");

    assertThat(storage.brokenValues()).contains(note.id(), shouted.id());
  }

  // ------------------------------------------------------------------ what a read acts on

  /**
   * A reader for memos would decode a note that says it is a memo; the commitment says otherwise.
   */
  @Test
  @DisplayName("refuses to hand over a value whose type was rewritten, and records the attempt")
  void refuses_a_retyped_value() throws SQLException {
    Occluded<Note> note = notes.occlude(new Note("hello"));

    execute("UPDATE occlude_value SET value_type = ? WHERE value_id = ?", MEMO.name(), note.id());

    Occluded<Memo> asMemo = Occluded.of(note.id());
    assertThatThrownBy(() -> memoDesk.reveal(asMemo)).isInstanceOf(StorageIntegrityException.class);
    assertThat(reasonsRecorded()).contains("NOT_AS_SIGNED");
    TypeRef<Memo> memoType = TypeRef.of(Memo.class);
    String id = note.id();
    assertThatThrownBy(() -> storage.value(id, memoType))
        .isInstanceOf(StorageIntegrityException.class);
  }

  @Test
  @DisplayName("refuses a value whose derivation was renamed")
  void refuses_a_renamed_derivation() throws SQLException {
    Occluded<Note> shouted = shout.derive(notes.occlude(new Note("hello"))).orThrow();

    execute("UPDATE occlude_value SET derivation = 'whisper' WHERE value_id = ?", shouted.id());

    String id = shouted.id();
    assertThatThrownBy(() -> storage.metadata(id)).isInstanceOf(StorageIntegrityException.class);
  }

  /**
   * Erasure walks the lineage table; a row removed from it would let a child outlive its parent.
   */
  @Test
  @DisplayName("refuses a value whose parentage was cut, rather than serving it as an orphan")
  void refuses_a_value_whose_parent_was_cut_away() throws SQLException {
    Occluded<Note> shouted = shout.derive(notes.occlude(new Note("hello"))).orThrow();

    execute("DELETE FROM occlude_lineage WHERE child_id = ?", shouted.id());

    String id = shouted.id();
    assertThatThrownBy(() -> storage.metadata(id)).isInstanceOf(StorageIntegrityException.class);
    assertThat(storage.brokenValues()).contains(shouted.id());
  }

  @Test
  @DisplayName("refuses a value that was given a parent it never had")
  void refuses_a_value_given_a_parent() throws SQLException {
    Occluded<Note> first = notes.occlude(new Note("first"));
    Occluded<Note> second = notes.occlude(new Note("second"));

    execute(
        "INSERT INTO occlude_lineage (child_id, parent_id, position) VALUES (?, ?, 0)",
        second.id(),
        first.id());

    String id = second.id();
    assertThatThrownBy(() -> storage.metadata(id)).isInstanceOf(StorageIntegrityException.class);
  }

  // ------------------------------------------------------------------ sweeping for it

  @Test
  @DisplayName("finds every value whose ciphertext was swapped, without waiting for a read")
  void finds_swapped_values() throws SQLException {
    Occluded<Note> first = notes.occlude(new Note("first"));
    Occluded<Note> second = notes.occlude(new Note("second"));
    notes.occlude(new Note("untouched"));

    execute(
        "UPDATE occlude_value SET payload = (SELECT payload FROM occlude_value WHERE value_id = ?)"
            + " WHERE value_id = ?",
        second.id(),
        first.id());

    Sweep sweep = storage.sweep();
    assertThat(sweep.alteredValues()).containsExactly(first.id());
    assertThat(sweep.intact()).isFalse();
    assertThat(storage.brokenValues()).isEmpty();
  }

  @Test
  @DisplayName("finds every line whose protected fields were swapped")
  void finds_swapped_lines() throws SQLException {
    notes.occlude(new Note("first"));
    memos.occlude(new Memo("second"));
    long first;
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT MIN(entry_id) FROM occlude_audit")) {
      rows.next();
      first = rows.getLong(1);
    }

    execute("UPDATE occlude_audit SET label = NULL WHERE entry_id = ?", first);

    Sweep sweep = storage.sweep();
    assertThat(sweep.alteredLines()).containsExactly(first);
    assertThat(sweep.alteredValues()).isEmpty();
    assertThat(sweep.intact()).isFalse();
    assertThat(storage.firstBrokenEntry()).isEmpty();
  }

  /** Destroying a key on purpose is erasure, not tampering, and must not read as an alarm. */
  @Test
  @DisplayName("reports what it has no key for as unreadable, never as altered")
  void reports_what_it_cannot_read_as_unreadable() {
    Occluded<Note> note = notes.occlude(new Note("hello"));

    JdbcStorage keyless =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(new JceDataKeyProvider("k2", Map.of("k2", TestKeys.aes256())))
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .storage(AXES);

    Sweep sweep = keyless.sweep();
    assertThat(sweep.intact()).isTrue();
    assertThat(sweep.unreadableValues()).containsExactly(note.id());
    assertThat(sweep.unreadableLines()).hasSize(1);
  }

  /** A frame this store never writes is not "unreadable": it is somebody else's bytes. */
  @Test
  @DisplayName("reports bytes that are not a frame this store writes as altered")
  void reports_foreign_bytes_as_altered() throws SQLException {
    Occluded<Note> note = notes.occlude(new Note("hello"));

    execute(
        "UPDATE occlude_value SET payload = 'not an envelope'::bytea WHERE value_id = ?",
        note.id());

    assertThat(storage.sweep().alteredValues()).containsExactly(note.id());
  }

  @Test
  @DisplayName("finds nothing altered in a store nobody touched")
  void finds_nothing_in_an_untouched_store() {
    Occluded<Note> note = notes.occlude(new Note("hello"));
    shout.derive(note).orThrow();
    noteDesk.reveal(note);

    Sweep sweep = storage.sweep();
    assertThat(sweep.intact()).isTrue();
    assertThat(sweep.unreadableValues()).isEmpty();
    assertThat(sweep.unreadableLines()).isEmpty();
  }
}
