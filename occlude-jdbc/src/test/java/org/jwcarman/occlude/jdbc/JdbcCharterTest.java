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

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.Bindings;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Derivation;
import org.jwcarman.occlude.Erased;
import org.jwcarman.occlude.Erasure;
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
import org.jwcarman.occlude.storage.Lineage;
import org.jwcarman.occlude.storage.StoredMetadata;
import org.jwcarman.occlude.storage.StoredValue;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The same policy as the in-memory billing scenario, against a real database.
 *
 * <p>Postgres rather than an embedded engine on purpose: a store that claims to work on Postgres
 * should be tried on Postgres, and {@code BYTEA}, {@code ON CONFLICT} and {@code TIMESTAMPTZ} are
 * exactly the places a compatibility mode diverges quietly.
 */
@Testcontainers
@DisplayName("A store in a database")
class JdbcCharterTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("store")
          .withUsername("store")
          .withPassword("store");

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  enum DataClass {
    NONE,
    PII,
    CARDHOLDER
  }

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Integrity> INTEGRITY =
      Axis.ladder("integrity", Integrity.ENDORSED, Integrity.UNENDORSED);
  private static final Axis<DataClass> DATA =
      Axis.ladder("dataClass", DataClass.NONE, DataClass.PII, DataClass.CARDHOLDER);

  record Card(String number, String holder) {}

  private static final OccludedType<Card> CARD = OccludedType.of(Card.class);

  record Last4(String digits) {}

  private static final OccludedType<Last4> LAST4 = OccludedType.of(Last4.class);

  private DataSource dataSource;
  private Erasure compliance;
  private JdbcStorage storage;
  private Derivation<Card, Last4> cardLast4;
  private Occlude<Card> cards;
  private Reveal<Card> vendorLlm;
  private Reveal<Card> paymentProcessor;
  private Occlude<List<Card>> cardLists;
  private Reveal<List<Card>> cardListProcessor;
  private Reveal<List<Card>> cardListVendor;
  private Reveal<List<Last4>> last4ListProcessor;

  /**
   * Standing in for the edge. A caller is not allowed to say who it is.
   *
   * <p>Per thread, as a real edge is: the concurrent tests act as different callers at once, and a
   * shared holder let one thread's identity overwrite another's mid-call.
   */
  private final ThreadLocal<AccessContext> edge = ThreadLocal.withInitial(AccessContext::empty);

  private AccessContext acme() {
    edge.set(AccessContext.of("tenant", "acme"));
    return AccessContext.empty();
  }

  /** What a value written on this access is labelled: the tenant comes from the access. */
  private static Label labelFor(AccessContext ctx, Integrity integrity, DataClass dataClass) {
    return ctx.get("tenant")
        .map(tenant -> Label.of(TENANT, tenant))
        .orElseGet(Label::nothing)
        .with(INTEGRITY, integrity)
        .with(DATA, dataClass);
  }

  private static Ceiling ceiling(AccessContext ctx, Integrity integrity, DataClass dataClass) {
    String tenant =
        ctx.get("tenant")
            .orElseThrow(
                () -> new IllegalStateException("this access says nothing about a tenant"));
    return Ceiling.of(TENANT, Constraint.atMost(tenant))
        .with(INTEGRITY, Constraint.atMost(integrity))
        .with(DATA, Constraint.atMost(dataClass));
  }

  @BeforeEach
  void setUp() throws Exception {
    PGSimpleDataSource pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    dataSource = pg;
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS occlude_audit, occlude_lineage, occlude_value");
    }

    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    SecretKey kek = generator.generateKey();

    DefaultCharter c = new DefaultCharter(TENANT, INTEGRITY, DATA);
    // Containers have to be named: their raw type is java.util.List, which is not ours to
    // annotate and would collide with every other list.
    OccludedType<List<Card>> cardList =
        OccludedType.of("card-list", TypeRef.listOf(TypeRef.of(Card.class)));
    OccludedType<List<Last4>> last4List =
        OccludedType.of("last4-list", TypeRef.listOf(TypeRef.of(Last4.class)));

    // The application supplies its keys and its root; what happens to the bytes is not its choice.
    JdbcStorageConfig jdbc =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(new JceDataKeyProvider("k1", Map.of("k1", kek)))
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root());

    // Erasure is the one operation a label cannot decide, so its policy is named here.
    compliance =
        c.erasure(
            "compliance",
            (label, ctx) ->
                ctx.has("role", "compliance")
                    && ctx.get("tenant").map(t -> label.says(TENANT, t)).orElse(false));

    // One source: everything this test holds is acme's cardholder data.
    cards = c.source("cards", CARD, ctx -> labelFor(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    vendorLlm =
        c.sink("vendor-llm", ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.NONE), CARD)
            .reading(CARD);
    // One sink, three readers. The ceiling is written once, every reader enforces it,
    // and all three audit under "payment-processor" because that is the subsystem they reach.
    // The subsystem, its ceiling, and everything it is allowed to read. Both restrictions are
    // settled here, so the readers below are typed views rather than grants.
    var processor =
        c.sink(
            "payment-processor",
            ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER),
            CARD,
            LAST4,
            cardList);
    paymentProcessor = processor.reading(CARD);
    processor.reading(LAST4);

    // A generic container is its own type, so it needs its own source and its own readers.
    cardLists =
        c.source(
            "card-lists", cardList, ctx -> labelFor(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    cardListProcessor = processor.reading(cardList);
    cardListVendor =
        c.sink(
                "card-lists-to-vendor",
                ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.NONE),
                cardList)
            .reading(cardList);
    last4ListProcessor =
        c.sink(
                "last4-lists-to-processor",
                ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER),
                last4List)
            .reading(last4List);

    cardLast4 =
        c.derivation(
            "Card.last4",
            CARD,
            LAST4,
            card -> new Last4(card.number().substring(card.number().length() - 4)),
            d ->
                d.accepting(ctx -> ceiling(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER))
                    .lowering(joined -> joined.with(DATA, DataClass.PII)));

    storage = jdbc.storage(c.axes());
    c.bind(Bindings.of(storage).withIdentity(edge::get));
  }

  /** What was written about a value, read beneath the charter, for assertions about state. */
  private Label labelOf(Occluded<?> occluded) {
    return storage.metadata(occluded.id()).orElseThrow().label();
  }

  private Lineage lineageOf(Occluded<?> occluded) {
    return storage.metadata(occluded.id()).orElseThrow().lineage();
  }

  private Occluded<Card> card() {
    acme();
    return cards.occlude(new Card("4111111111114821", "J CARMAN"));
  }

  @Test
  @DisplayName("keeps a value and gives it back to somewhere allowed to have it")
  void keeps_a_value_and_gives_it_back() {
    Occluded<Card> card = card();

    acme();
    assertThat(paymentProcessor.reveal(card).value())
        .contains(new Card("4111111111114821", "J CARMAN"));
    acme();
    assertThat(vendorLlm.reveal(card).succeeded()).isFalse();
  }

  /** The point of the whole module: what is on disk is not the value. */
  @Test
  @DisplayName("stores no plaintext, not the value and not the label either")
  void stores_no_plaintext() throws SQLException {
    Occluded<Card> card = card();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT payload, label FROM occlude_value WHERE value_id = ?")) {
      statement.setString(1, card.id());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        String payload = new String(rows.getBytes("payload"));
        String label = new String(rows.getBytes("label"));
        assertThat(payload).doesNotContain("4111111111114821").doesNotContain("CARMAN");
        assertThat(label).doesNotContain("acme").doesNotContain("CARDHOLDER");
      }
    }
  }

  /**
   * The trail is queryable and closed at the same time.
   *
   * <p>A refusal's code names a rule, so it stays in the clear and "how many refusals above a
   * ceiling this hour" needs no key. What the refusal <i>would have said</i> -- which label, which
   * ceiling -- names a tenant and its data class, so it goes to disk the way a label does. In the
   * clear it would describe every value in the system to anyone who could read this table, which is
   * the same disclosure the caller is refused.
   */
  @Test
  @DisplayName("records why it refused without putting the explanation in the clear")
  void records_why_without_disclosing_it() throws SQLException {
    Occluded<Card> card = card();
    edge.set(AccessContext.of("tenant", "acme"));

    assertThat(vendorLlm.reveal(card).succeeded()).isFalse();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT reason, detail FROM occlude_audit WHERE outcome = 'REFUSED'")) {
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("reason")).isEqualTo("ABOVE_CEILING");
        String detail = new String(rows.getBytes("detail"));
        assertThat(detail).doesNotContain("acme").doesNotContain("CARDHOLDER");
      }
    }
  }

  /**
   * Every value hashes from its own bytes and whatever it was made from.
   *
   * <p>A fresh value starts its own graph. Everything derived from it hashes from parents that are
   * immutable and already written, so nothing is locked and no global order exists -- a value is
   * fixed by its ancestry, not by when it arrived.
   */
  @Test
  @DisplayName("writes values whose digests agree with their ancestry")
  void writes_values_whose_digests_agree() {
    Occluded<Card> card = card();

    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    assertThat(last4).isNotNull();
    assertThat(storage.brokenValues()).isEmpty();
  }

  /**
   * The digests cover what a value commits to, not its ciphertext, so an edited ciphertext is
   * caught where it matters: the moment somebody reads it.
   */
  @Test
  @DisplayName("refuses to read a value whose ciphertext was edited")
  void refuses_to_read_an_edited_ciphertext() throws SQLException {
    Occluded<Card> card = card();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_value SET payload = ? WHERE value_id = ?")) {
      statement.setBytes(1, "not what was stored".getBytes(StandardCharsets.UTF_8));
      statement.setString(2, card.id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    TypeRef<Card> cardType = TypeRef.of(Card.class);
    String id = card.id();
    assertThatThrownBy(() -> storage.value(id, cardType)).isInstanceOf(RuntimeException.class);
  }

  /**
   * A well-formed ciphertext copied from another row decrypts perfectly -- same key, same pipeline.
   * Only its commitment, bound to the row it was written for, can tell.
   */
  @Test
  @DisplayName("refuses to read a ciphertext copied in from another value")
  void refuses_a_ciphertext_copied_from_another_value() throws SQLException {
    Occluded<Card> mine = card();
    // Another tenant's, so its label says something different from mine and a swapped label would
    // move my card into globex's reach.
    edge.set(AccessContext.of("tenant", "globex"));
    Occluded<Card> other = cards.occlude(new Card("4000056655665556", "SOMEBODY ELSE"));

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_value SET payload = (SELECT payload FROM occlude_value WHERE"
                    + " value_id = ?), label = (SELECT label FROM occlude_value WHERE value_id = ?)"
                    + " WHERE value_id = ?")) {
      statement.setString(1, other.id());
      statement.setString(2, other.id());
      statement.setString(3, mine.id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    TypeRef<Card> cardType = TypeRef.of(Card.class);
    String id = mine.id();
    assertThatThrownBy(() -> storage.value(id, cardType))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not what was signed");
    assertThatThrownBy(() -> storage.metadata(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not what was signed");
  }

  @Test
  @DisplayName("notices a value whose commitment was edited, and everything derived from it")
  void notices_an_edited_value() throws SQLException {
    Occluded<Card> card = card();
    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_value SET payload_commitment = ? WHERE value_id = ?")) {
      statement.setBytes(1, "not what was signed".getBytes(StandardCharsets.UTF_8));
      statement.setString(2, card.id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    // The value itself, and the one made from what it used to be.
    assertThat(storage.brokenValues()).contains(card.id(), last4.id());
  }

  /** Removing one leaves its children hashing from something that is not there. */
  @Test
  @DisplayName("notices a value somebody deleted, through the children it left behind")
  void notices_a_deleted_value() throws SQLException {
    Occluded<Card> card = card();
    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement("DELETE FROM occlude_value WHERE value_id = ?")) {
      statement.setString(1, card.id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    assertThat(storage.brokenValues()).contains(last4.id());
  }

  /**
   * The case the value graph is blind to.
   *
   * <p>A value's digest binds it to its ancestry, so deleting one is caught by the children it
   * orphans. A leaf has none. Nothing derived from it, nothing to disagree, and a verifier that
   * walks the rows that are still there cannot miss what is not there.
   *
   * <p>The trail is outside the row, which is the whole reason it can answer this.
   */
  @Test
  @DisplayName("notices a leaf value somebody deleted, which left no children to notice it")
  void notices_a_deleted_leaf() throws SQLException {
    Occluded<Card> card = card();

    assertThat(storage.missingValues()).isEmpty();
    deleteValue(card.id());

    assertThat(storage.brokenValues()).isEmpty();
    assertThat(storage.missingValues()).containsExactly(card.id());
  }

  /** And a value this library was asked to erase is not reported missing, because it said so. */
  @Test
  @DisplayName("does not confuse a lawful erasure with a deletion")
  void does_not_confuse_erasure_with_deletion() {
    Occluded<Card> card = card();
    acme();
    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));
    assertThat(compliance.erase(card).orThrow()).isEqualTo(2);

    assertThat(storage.contains(card.id())).isFalse();
    assertThat(storage.contains(last4.id())).isFalse();
    assertThat(storage.missingValues()).isEmpty();
    assertThat(storage.firstBrokenEntry()).isEmpty();
  }

  /**
   * An erasure that fails partway must leave nothing behind, least of all false evidence.
   *
   * <p>When the deletes committed separately from the lines, a failure between them destroyed
   * values the trail never said were destroyed -- which is exactly what an out-of-band deletion
   * looks like. The chain still verified, so "check the trail first" did not help, and erasing
   * again found nothing to erase, so nothing could repair it. A permanent tamper alarm for
   * something nobody did, which is the way a verifier stops being believed.
   */
  @Test
  @DisplayName("an erasure that fails partway leaves the values and reports no tampering")
  void an_erasure_that_fails_partway_leaves_no_false_alarm() {
    Occluded<Card> card = card();
    acme();
    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    // Fails on the second line, standing in for a dropped connection midway through.
    AtomicInteger written = new AtomicInteger();
    Function<String, AuditRecord> failing =
        id -> {
          if (written.incrementAndGet() == 2) {
            throw new IllegalStateException("connection dropped mid-erase");
          }
          return new AuditRecord(
              AuditRecord.Operation.ERASE,
              id,
              Optional.of(card.id()),
              AuditRecord.Outcome.ALLOWED,
              Optional.of("erased"),
              Optional.empty(),
              Optional.empty(),
              Map.of());
        };
    String cardId = card.id();

    assertThatThrownBy(() -> storage.erase(cardId, failing)).isInstanceOf(RuntimeException.class);

    // The whole transaction rolled back: the values are still here, so nothing is missing and the
    // verifier reports no tampering. Written separately, these two assertions both failed.
    assertThat(storage.contains(card.id())).isTrue();
    assertThat(storage.contains(last4.id())).isTrue();
    assertThat(storage.missingValues()).isEmpty();
    assertThat(storage.firstBrokenEntry()).isEmpty();
  }

  /**
   * A verifier that crashes reports nothing, which is what an attacker wants.
   *
   * <p>Rewriting a line's digest is noticed. Rewriting its root_id used to be better than that: it
   * named a root nothing supplies, the lookup threw, and the whole report became an exception
   * instead of a finding. Turning a detection into an outage is an upgrade for whoever did it.
   */
  @Test
  @DisplayName("reports a line naming a root nobody supplies, rather than throwing")
  void reports_a_line_whose_root_is_unknown() throws SQLException {
    card();
    assertThat(storage.firstBrokenEntry()).isEmpty();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_audit SET root_id = ? WHERE entry_id = 1")) {
      statement.setString(1, "a-root-nobody-has");
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }

    assertThat(storage.firstBrokenEntry()).contains(1L);
  }

  /**
   * Deriving and erasing must not be able to miss each other.
   *
   * <p>A share lock on the parent rows is not enough: under READ COMMITTED a blocked DELETE resumes
   * with the snapshot its statement began with, so an erasure can take its snapshot, wait behind a
   * derivation, and then delete only what it saw -- leaving the child that committed in between
   * alive, with its parent gone and no ERASE line naming it.
   *
   * <p>Run as contention rather than as one contrived interleaving, because the interleaving that
   * breaks it is a matter of timing. Every surviving value must still verify: nothing may be left
   * pointing at a parent that was erased.
   */
  @Test
  @DisplayName("deriving while erasing never leaves a child of an erased value behind")
  void deriving_while_erasing_leaves_nothing_behind() throws Exception {
    int rounds = 24;
    List<Callable<Void>> work = new ArrayList<>();
    for (int round = 0; round < rounds; round++) {
      work.add(
          () -> {
            Occluded<Card> card = card();
            Callable<Void> deriving =
                () -> {
                  acme();
                  try {
                    cardLast4.derive(card);
                  } catch (IllegalArgumentException _) {
                    // The parent went first. That is a legitimate outcome of the race.
                  }
                  return null;
                };
            Callable<Void> erasing =
                () -> {
                  edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));
                  compliance.erase(card).orThrow();
                  return null;
                };
            // Through futures rather than bare threads, so a failure on either side fails the
            // test instead of printing a stack trace and passing.
            try (ExecutorService pair = Executors.newFixedThreadPool(2)) {
              for (Future<Void> done : pair.invokeAll(List.of(deriving, erasing))) {
                done.get();
              }
            }
            return null;
          });
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      for (Future<Void> done : pool.invokeAll(work)) {
        done.get();
      }
    }

    // Whatever survived is coherent: nothing orphaned, nothing missing, the trail intact.
    assertThat(storage.brokenValues()).isEmpty();
    assertThat(storage.missingValues()).isEmpty();
    assertThat(storage.firstBrokenEntry()).isEmpty();
  }

  private void deleteValue(String id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement("DELETE FROM occlude_value WHERE value_id = ?")) {
      statement.setString(1, id);
      assertThat(statement.executeUpdate()).isPositive();
    }
  }

  /**
   * The trail is a chain, because a line has a predecessor rather than parents.
   *
   * <p>Values hash from their ancestry and need no order. Accesses have no ancestry -- a refused
   * read makes nothing -- so the only thing a line can name is the one before it.
   */
  @Test
  @DisplayName("writes a trail that verifies")
  void writes_a_trail_that_verifies() {
    card();
    edge.set(AccessContext.of("tenant", "acme"));
    vendorLlm.reveal(card());

    assertThat(storage.firstBrokenEntry()).isEmpty();
  }

  @Test
  @DisplayName("notices a line somebody edited")
  void notices_an_edited_line() throws SQLException {
    Occluded<Card> card = card();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_audit SET outcome = 'REFUSED' WHERE value_id = ?")) {
      statement.setString(1, card.id());
      assertThat(statement.executeUpdate()).isPositive();
    }

    assertThat(storage.firstBrokenEntry()).isPresent();
  }

  /**
   * And notices a deletion that was covered up, which is the one that matters.
   *
   * <p>Removing a line and leaving it is easy to catch. The real case is somebody who removes one
   * and then repairs the chain behind it -- re-pointing what followed at what preceded, so the
   * trail reads as though the line never existed. That works against a plain hash chain. It does
   * not work here, because the digests are keyed and they do not have the key.
   */
  @Test
  @DisplayName("notices a deletion even when somebody repaired the chain behind it")
  void notices_a_covered_up_deletion() throws SQLException {
    card();
    edge.set(AccessContext.of("tenant", "acme"));
    vendorLlm.reveal(card());
    card();
    assertThat(storage.firstBrokenEntry()).isEmpty();

    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      // Take out the second line and stitch the third onto the first, the way somebody covering
      // their tracks would. Every digest still looks locally plausible.
      statement.executeUpdate(
          """
          UPDATE occlude_audit SET previous = (
              SELECT previous FROM occlude_audit ORDER BY entry_id OFFSET 1 LIMIT 1)
          WHERE entry_id = (SELECT entry_id FROM occlude_audit ORDER BY entry_id OFFSET 2 LIMIT 1)
          """);
      statement.executeUpdate(
          "DELETE FROM occlude_audit WHERE entry_id ="
              + " (SELECT entry_id FROM occlude_audit ORDER BY entry_id OFFSET 1 LIMIT 1)");
    }

    assertThat(storage.firstBrokenEntry()).isPresent();
  }

  /**
   * And the key is what makes any of that hold against somebody determined.
   *
   * <p>The test above catches a repair that re-pointed the chain without re-signing it. A careful
   * attacker would re-sign, and against an unkeyed chain that works -- recompute everything after
   * the gap and it agrees with itself again. They cannot, because the digests are an HMAC under a
   * root this database does not hold.
   *
   * <p>That property cannot be demonstrated by a test holding the key. What can be shown is that
   * the digests depend on it: read the same intact trail under a different root and every line
   * disagrees.
   */
  @Test
  @DisplayName("cannot be verified, or forged, without the root it was written under")
  void cannot_be_verified_without_the_root() {
    card();
    assertThat(storage.firstBrokenEntry()).isEmpty();

    JdbcStorage underAnotherRoot =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn(
                "open",
                "somebody else's key -- long enough to be a root".getBytes(StandardCharsets.UTF_8))
            .withoutMigration()
            .storage(Axes.of(TENANT, INTEGRITY, DATA));

    assertThat(underAnotherRoot.firstBrokenEntry()).isPresent();
  }

  /**
   * A root can be rotated without invalidating what was written under the last one.
   *
   * <p>Every value and every line records which root signed it, so verifying asks for that one.
   * Rotating writes new rows under the new root and leaves the old ones readable -- the
   * alternative, re-signing everything on the way past, is the one operation an append-only trail
   * must not support.
   */
  @Test
  @DisplayName("verifies what an older root signed after a new one takes over")
  void verifies_across_a_rotation() {
    byte[] first = "the first root -- long enough to be a root".getBytes(StandardCharsets.UTF_8);
    byte[] second = "the second root -- long enough to be a root".getBytes(StandardCharsets.UTF_8);
    Axes axes = Axes.of(TENANT, INTEGRITY, DATA);

    DefaultCharter under1 = new DefaultCharter(axes);
    Occlude<Card> early =
        under1.source(
            "cards", CARD, ctx -> labelFor(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    under1.bind(Bindings.of(rooted("r1", Map.of("r1", first), axes)).withIdentity(edge::get));
    edge.set(AccessContext.of("tenant", "acme"));
    early.occlude(new Card("4111111111114821", "CARMAN"));

    Map<String, byte[]> both = Map.of("r1", first, "r2", second);
    DefaultCharter under2 = new DefaultCharter(axes);
    Occlude<Card> later =
        under2.source(
            "cards", CARD, ctx -> labelFor(ctx, Integrity.ENDORSED, DataClass.CARDHOLDER));
    JdbcStorage rotated = rooted("r2", both, axes);
    under2.bind(Bindings.of(rotated).withIdentity(edge::get));
    later.occlude(new Card("4111111111119999", "CARMAN"));

    // Both eras, one verification, and nothing had to be re-signed.
    assertThat(rotated.firstBrokenEntry()).isEmpty();
    assertThat(rotated.brokenValues()).isEmpty();
  }

  private JdbcStorage rooted(String id, Map<String, byte[]> roots, Axes axes) {
    return new JdbcStorageConfig()
        .dataSource(dataSource)
        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
        .encryptedWith(TestKeys.dataKeys())
        .rootedIn(id, roots::get)
        .withoutMigration()
        .storage(axes);
  }

  /**
   * What this cannot catch, stated as a test so nobody has to discover it.
   *
   * <p>Cutting lines off the end leaves a chain that verifies, because what remains is exactly the
   * trail as it stood earlier. Nothing inside the database knows the removed lines ever existed --
   * and that is not a gap in the implementation, it is what truncation is. No structure over data
   * an attacker controls can tell you about entries they deleted.
   *
   * <p>Closing it takes something outside: the head digest published where whoever can write to
   * this database cannot reach, and checked afterwards with {@link JdbcStorage#stillHolds}.
   */
  @Test
  @DisplayName("cannot notice lines cut from the end, which is what an anchor is for")
  void cannot_notice_a_truncation() throws SQLException {
    card();
    edge.set(AccessContext.of("tenant", "acme"));
    vendorLlm.reveal(card());
    TrailHead anchored = storage.head().orElseThrow();
    assertThat(storage.stillHolds(anchored)).isTrue();

    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      assertThat(
              statement.executeUpdate(
                  "DELETE FROM occlude_audit WHERE entry_id ="
                      + " (SELECT MAX(entry_id) FROM occlude_audit)"))
          .isPositive();
    }

    // The chain still agrees with itself, which is exactly the problem.
    assertThat(storage.firstBrokenEntry()).isEmpty();
    // And the head somebody wrote down elsewhere is what gives it away.
    assertThat(storage.stillHolds(anchored)).isFalse();
    assertThat(storage.head()).isNotEqualTo(Optional.of(anchored));
  }

  @Test
  @DisplayName("a fresh store over the same database reads what the last one wrote")
  void survives_a_restart() {
    Occluded<Card> card = card();

    assertThat(storage.contains(card.id())).isTrue();
    assertThat(labelOf(card).says(DATA, DataClass.CARDHOLDER)).isTrue();
  }

  @Test
  @DisplayName("a derived value keeps its parentage and its lowered label")
  void a_derived_value_keeps_its_parentage() {
    Occluded<Card> card = card();

    acme();
    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    assertThat(labelOf(last4).says(DATA, DataClass.PII)).isTrue();
    assertThat(lineageOf(last4).parents()).containsExactly(card.id());
    assertThat(lineageOf(last4).derivation()).contains("Card.last4");
  }

  @Test
  @DisplayName("deriving the same thing twice stores it twice, and says so")
  void deriving_twice_stores_twice() throws SQLException {
    Occluded<Card> card = card();

    acme();
    Occluded<Last4> once = cardLast4.derive(card).orThrow();
    acme();
    Occluded<Last4> twice = cardLast4.derive(card).orThrow();

    assertThat(once.id()).isNotEqualTo(twice.id());
    assertThat(rowCount("occlude_value")).isEqualTo(3);
  }

  /** Erasure is a reachability query, walked over the lineage the value digests cover. */
  @Test
  @DisplayName("erasing a value takes everything ever derived from it")
  void erasing_takes_everything_derived_from_it() {
    Occluded<Card> card = card();
    acme();
    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));
    int removed = compliance.erase(card).orThrow();

    assertThat(removed).isEqualTo(2);
    assertThat(storage.contains(card.id())).isFalse();
    assertThat(storage.contains(last4.id())).isFalse();
  }

  @Test
  @DisplayName("erasing a derived value leaves its parent alone")
  void erasing_a_derived_value_leaves_its_parent() {
    Occluded<Card> card = card();
    acme();
    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));
    assertThat(compliance.erase(last4).orThrow()).isEqualTo(1);
    assertThat(storage.contains(card.id())).isTrue();
  }

  @Test
  @DisplayName("another tenant's access is refused, whatever is on disk")
  void another_tenants_access_is_refused() {
    Occluded<Card> card = card();

    assertThat(revealAs("globex", card)).isFalse();
  }

  @Test
  @DisplayName("refuses to be built without keys to encrypt with")
  void refuses_to_be_built_without_keys() {
    assertThat(
            Assertions.catchThrowable(
                () ->
                    new JdbcStorageConfig()
                        .dataSource(dataSource)
                        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
                        .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
                        .storage(Axes.of(TENANT, INTEGRITY, DATA))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("encryptedWith");
  }

  /** A store used to be rooted in a published constant unless told otherwise, and forgeable. */
  @Test
  @DisplayName("refuses to be built without a secret root")
  void refuses_to_be_built_without_a_root() {
    assertThat(
            Assertions.catchThrowable(
                () ->
                    new JdbcStorageConfig()
                        .dataSource(dataSource)
                        .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
                        .encryptedWith(TestKeys.dataKeys())
                        .storage(Axes.of(TENANT, INTEGRITY, DATA))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rootedIn");
  }

  /** A different tenant, established at the edge rather than claimed by the caller. */
  private boolean revealAs(String tenant, Occluded<Card> card) {
    edge.set(AccessContext.of("tenant", tenant));
    return paymentProcessor.reveal(card).succeeded();
  }

  /** The trail goes to the database, in the same transaction as the thing it describes. */
  @Test
  @DisplayName("writes the record beside the value it is about")
  void writes_the_record_beside_the_value() throws SQLException {
    assertThat(rowCount("occlude_audit")).isZero();

    Occluded<Card> card = card();
    acme();
    paymentProcessor.reveal(card);

    // One line for taking it in, one for handing it over.
    assertThat(rowCount("occlude_audit")).isEqualTo(2);
    assertThat(auditColumn("operation")).containsExactly("CONCEAL", "REVEAL");
    assertThat(auditColumn("outcome")).containsOnly("ALLOWED");
  }

  /**
   * The trail outlives what it describes, and that is the whole point of it.
   *
   * <p>Erasing a customer takes their values and everything derived from them. The record that it
   * happened has to survive that, or the system cannot prove it did the thing it was required to
   * do. So occlude_audit has no foreign key to occlude_value and nothing cascades into it.
   */
  @Test
  @DisplayName("and keeps it after the value it is about has been erased")
  void keeps_the_record_after_erasure() throws SQLException {
    Occluded<Card> card = card();
    acme();
    cardLast4.derive(card);
    int before = rowCount("occlude_audit");

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));
    compliance.erase(card).orThrow();

    // Two values erased, so two ERASE lines, and every earlier line is still there. ">= before"
    // was a tautology: the trail never shrinks, which is the property being claimed, not evidence.
    assertThat(rowCount("occlude_value")).isZero();
    assertThat(rowCount("occlude_audit")).isEqualTo(before + 2);
    assertThat(auditColumn("operation")).filteredOn("ERASE"::equals).hasSize(2);
    assertThat(auditColumn("outcome")).containsOnly("ALLOWED");
  }

  private List<String> auditColumn(String column) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        ResultSet rows =
            connection
                .createStatement()
                .executeQuery("SELECT " + column + " FROM occlude_audit ORDER BY entry_id")) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }

  private int rowCount(String table) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        ResultSet rows =
            connection.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
      return rows.next() ? rows.getInt(1) : 0;
    }
  }

  /**
   * The walk includes the value it starts from, which is the whole of erasing a leaf.
   *
   * <p>Asserted through behaviour rather than through a row count. This used to check that a
   * closure table held a value as its own ancestor -- a fact about a structure that no longer
   * exists, which would have kept passing while erasure was broken, or failing while it worked.
   */
  @Test
  @DisplayName("erasing a value nothing was derived from removes exactly that value")
  void erasing_a_leaf_removes_exactly_it() throws SQLException {
    Occluded<Card> card = card();
    acme();
    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));

    assertThat(compliance.erase(card).orThrow()).isEqualTo(1);
    assertThat(rowCount("occlude_value")).isZero();
  }

  @Test
  @DisplayName("lineage of a held value says it was asserted, not computed")
  void lineage_of_a_held_value_says_asserted() {
    assertThat(lineageOf(card()).asserted()).isTrue();
    // Nothing made it, so it has no parents. This used to wrap the parents list in ANOTHER list
    // and assert that was non-empty, which is true of every list, and said the opposite besides.
    assertThat(lineageOf(card()).parents()).isEmpty();
  }

  /**
   * Nothing about what a value says reaches its stored length, beyond how long it is.
   *
   * <p>A store used to compress before encrypting, so a repetitive value was stored far smaller
   * than a varied one of the same length -- the length leak CRIME and BREACH exploit. Encrypted
   * without compression, every payload is its plaintext plus the same fixed overhead.
   */
  @Test
  @DisplayName("stores every value at its plaintext length plus the same fixed overhead")
  void stores_every_value_at_its_length_plus_a_fixed_overhead() throws SQLException {
    Card ordinary = new Card("4111111111114821", "J CARMAN");
    Card repetitive = new Card("4111111111114821", "J CARMAN ".repeat(200));
    acme();
    Occluded<Card> small = cards.occlude(ordinary);
    Occluded<Card> large = cards.occlude(repetitive);

    int smallOverhead = payloadLength(small) - serialised(ordinary);
    int largeOverhead = payloadLength(large) - serialised(repetitive);

    assertThat(smallOverhead).isPositive().isEqualTo(largeOverhead);
  }

  private static int serialised(Card card) {
    return new JacksonCodecFactory(JsonMapper.builder().build())
        .create(Card.class)
        .encode(card)
        .length;
  }

  private int payloadLength(Occluded<?> held) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT payload FROM occlude_value WHERE value_id = ?")) {
      statement.setString(1, held.id());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBytes("payload").length;
      }
    }
  }

  /**
   * A value's class is not its type. {@code List.of(a, b).getClass()} is {@code
   * ImmutableCollections$List12}, which nothing can deserialise into, so a store that guessed from
   * the object would write a handle it could never honour. The caller says what it is.
   */
  @Test
  @DisplayName("holds a generic container and gives it back")
  void holds_a_generic_container() {
    List<Card> cardBatch =
        List.of(new Card("4111111111114821", "A"), new Card("4111111111119999", "B"));

    acme();
    Occluded<List<Card>> held = cardLists.occlude(cardBatch);

    acme();
    assertThat(cardListProcessor.reveal(held).value())
        .hasValueSatisfying(
            back -> {
              assertThat(back).hasSize(2);
              assertThat(back.getFirst().number()).isEqualTo("4111111111114821");
            });
    acme();
    assertThat(cardListVendor.reveal(held).succeeded()).isFalse();
  }

  @Test
  @DisplayName("a handle claiming the wrong element type is refused")
  void a_handle_claiming_the_wrong_element_type_is_refused() {
    acme();
    Occluded<List<Card>> occluded = cardLists.occlude(List.of(new Card("4111111111114821", "A")));
    Occluded<List<Last4>> lying = Occluded.of(occluded.id());

    acme();
    assertThat(last4ListProcessor.reveal(lying).succeeded()).isFalse();
  }

  /** Reading a label should not decrypt a payload. */
  @Test
  @DisplayName("asking what a value is labelled does not decode the value")
  void asking_for_a_label_does_not_decode_the_value() {
    acme();
    Occluded<List<Card>> occluded = cardLists.occlude(List.of(new Card("4111111111114821", "A")));

    // No type is supplied here, and none is needed: the label is read without touching the payload.
    assertThat(labelOf(occluded).says(DATA, DataClass.CARDHOLDER)).isTrue();
    assertThat(lineageOf(occluded).asserted()).isTrue();
  }

  /** A label governs disclosure, not destruction, so erasure is named separately or not granted. */
  @Test
  @DisplayName("refuses to erase for anyone the application did not name")
  void refuses_to_erase_for_anyone_not_named() {
    Occluded<Card> card = card();
    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "agent")));

    assertThat(compliance.erase(card))
        .isInstanceOfSatisfying(
            Erased.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(Erased.Reason.NOT_PERMITTED));
    assertThat(storage.contains(card.id())).isTrue();
  }

  private AuditRecord aQueryLine(String value) {
    return new AuditRecord(
        AuditRecord.Operation.QUERY,
        value,
        Optional.empty(),
        AuditRecord.Outcome.ALLOWED,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Map.of());
  }

  /** A data source nothing can ever connect through, so every write on it fails the same way. */
  private DataSource unreachableDataSource() {
    PGSimpleDataSource bad = new PGSimpleDataSource();
    bad.setServerNames(new String[] {"127.0.0.1"});
    bad.setPortNumbers(new int[] {1});
    bad.setDatabaseName("nope");
    bad.setConnectTimeout(1);
    return bad;
  }

  @Test
  @DisplayName("cannot create the schema when the database cannot be reached")
  void cannot_migrate_when_the_database_is_unreachable() {
    JdbcStorageConfig unreachableConfig =
        new JdbcStorageConfig()
            .dataSource(unreachableDataSource())
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root());
    Axes axes = Axes.of(TENANT, INTEGRITY, DATA);

    assertThatThrownBy(() -> unreachableConfig.storage(axes))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not create the store schema");
  }

  @Test
  @DisplayName("cannot write when the database cannot be reached")
  void cannot_write_when_the_database_is_unreachable() {
    JdbcStorage unreachable =
        new JdbcStorageConfig()
            .dataSource(unreachableDataSource())
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .withoutMigration()
            .storage(Axes.of(TENANT, INTEGRITY, DATA));
    AuditRecord line = aQueryLine("x");

    assertThatThrownBy(() -> unreachable.append(line))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not record");
  }

  /** A connection whose {@code rollback()} always fails, everything else left alone. */
  private Connection connectionWhoseRollbackFails(Connection real) {
    return connectionThatFails(real, "rollback", "rollback refused");
  }

  /** A connection whose {@code close()} always fails, everything else left alone. */
  private Connection connectionWhoseCloseFails(Connection real) {
    return connectionThatFails(real, "close", "close refused");
  }

  private Connection connectionThatFails(Connection real, String methodName, String message) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (methodName.equals(method.getName()) && method.getParameterCount() == 0) {
            throw new SQLException(message);
          }
          try {
            return method.invoke(real, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        };
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  private DataSource dataSourceWhoseRollbackFails() {
    return dataSourceWrapping(this::connectionWhoseRollbackFails);
  }

  private DataSource dataSourceWhoseCloseFails() {
    return dataSourceWrapping(this::connectionWhoseCloseFails);
  }

  /** Every connection this data source hands out is real, wrapped by the given failure. */
  private DataSource dataSourceWrapping(UnaryOperator<Connection> wrapper) {
    return new DataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        return wrapper.apply(dataSource.getConnection());
      }

      @Override
      public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public PrintWriter getLogWriter() {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public void setLogWriter(PrintWriter out) {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public void setLoginTimeout(int seconds) {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public int getLoginTimeout() {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public Logger getParentLogger() {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public <T> T unwrap(Class<T> iface) {
        throw new UnsupportedOperationException("not needed by this test");
      }

      @Override
      public boolean isWrapperFor(Class<?> iface) {
        throw new UnsupportedOperationException("not needed by this test");
      }
    };
  }

  /**
   * Committed separately, a failed rollback would be free to replace the exception that explains
   * why the write actually failed. It must not: the write's own failure is what a caller needs.
   */
  @Test
  @DisplayName("keeps the original failure when the rollback that follows it fails too")
  void keeps_the_original_failure_when_rollback_also_fails() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_audit");
    }
    JdbcStorage overFailingRollback =
        new JdbcStorageConfig()
            .dataSource(dataSourceWhoseRollbackFails())
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .withoutMigration()
            .storage(Axes.of(TENANT, INTEGRITY, DATA));
    AuditRecord line = aQueryLine("x");

    assertThatThrownBy(() -> overFailingRollback.append(line))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not record")
        .hasCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("reports a value naming a root nobody supplies, rather than throwing")
  void reports_a_value_whose_root_is_unknown() {
    Axes axes = Axes.of(TENANT, INTEGRITY, DATA);
    JdbcStorage writer =
        rooted(
            "r1",
            Map.of(
                "r1", "first secret -- long enough to be a root".getBytes(StandardCharsets.UTF_8)),
            axes);
    StoredValue value =
        new StoredValue(
            "a note",
            OccludedType.of("note", String.class),
            Label.of(TENANT, "acme"),
            Lineage.occluded());
    writer.put(
        "note-1",
        value,
        new AuditRecord(
            AuditRecord.Operation.CONCEAL,
            "note-1",
            Optional.empty(),
            AuditRecord.Outcome.ALLOWED,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Map.of()));

    // "r1" was the writer's root. This reader has never heard of it.
    JdbcStorage reader =
        rooted(
            "r2",
            Map.of(
                "r2", "second secret -- long enough to be a root".getBytes(StandardCharsets.UTF_8)),
            axes);

    assertThat(reader.brokenValues()).containsExactly("note-1");
  }

  @Test
  @DisplayName("refuses to be built under a root nobody configured")
  void refuses_to_be_built_under_a_root_nobody_configured() {
    Axes axes = Axes.of(TENANT, INTEGRITY, DATA);
    Map<String, byte[]> none = Map.of();

    assertThatThrownBy(() -> rooted("ghost", none, axes))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("the root 'ghost' needs a secret of at least 32 bytes");
  }

  /** The whole design's forgery resistance is the root's entropy, so a short one is refused. */
  @Test
  @DisplayName("refuses a root shorter than 32 bytes")
  void refuses_a_short_root() {
    JdbcStorageConfig config =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys());
    byte[] shortSecret = "too short".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> config.rootedIn("r1", shortSecret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 32 bytes");
  }

  @Test
  @DisplayName("reports a line naming a root truly nobody supplies, rather than throwing")
  void reports_a_line_whose_root_is_truly_unknown() throws SQLException {
    JdbcStorage rootedStorage =
        rooted(
            "r1",
            Map.of("r1", "secret -- long enough to be a root".getBytes(StandardCharsets.UTF_8)),
            Axes.of(TENANT, INTEGRITY, DATA));
    rootedStorage.append(aQueryLine("x"));
    assertThat(rootedStorage.firstBrokenEntry()).isEmpty();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_audit SET root_id = ? WHERE entry_id = 1")) {
      statement.setString(1, "nobody-has-this");
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }

    assertThat(rootedStorage.firstBrokenEntry()).contains(1L);
  }

  @Test
  @DisplayName("notices the first line's predecessor is wrong, even though nothing precedes it")
  void notices_the_first_lines_predecessor_is_wrong() throws SQLException {
    card();
    assertThat(storage.firstBrokenEntry()).isEmpty();

    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "UPDATE occlude_audit SET previous = ? WHERE entry_id = 1")) {
      statement.setBytes(1, "not null".getBytes(StandardCharsets.UTF_8));
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }

    assertThat(storage.firstBrokenEntry()).contains(1L);
  }

  @Test
  @DisplayName("an empty trail has no head")
  void head_of_an_empty_trail_is_empty() {
    assertThat(storage.head()).isEmpty();
  }

  @Test
  @DisplayName("the head reports a broken connection rather than corrupting silently")
  void head_reports_when_the_table_is_gone() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_audit");
    }

    assertThatThrownBy(storage::head)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read the head of the trail");
  }

  @Test
  @DisplayName("sweeping reports a broken connection rather than answering")
  void sweep_reports_when_the_table_is_gone() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_audit");
    }

    assertThatThrownBy(storage::sweep)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not sweep the store");
  }

  @Test
  @DisplayName("checking an anchor reports a broken connection rather than answering")
  void still_holds_reports_when_the_table_is_gone() throws SQLException {
    TrailHead anchor = new TrailHead(1, new byte[] {1});
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_audit");
    }

    assertThatThrownBy(() -> storage.stillHolds(anchor))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not look for the anchor");
  }

  /**
   * A root lookup that answers with an empty secret is as good as one that does not answer: the
   * rows under it are unverifiable, and the report says so rather than crashing.
   */
  @Test
  @DisplayName("treats an empty root secret as a missing root")
  void treats_an_empty_root_as_missing() {
    card();
    byte[] current = "a newer root, long enough to be one".getBytes(StandardCharsets.UTF_8);
    // The rows were signed under TestKeys.ROOT_ID; a later lookup answers for it with nothing.
    JdbcStorage emptyRoot =
        rooted(
            "r2",
            Map.of("r2", current, TestKeys.ROOT_ID, new byte[0]),
            Axes.of(TENANT, INTEGRITY, DATA));

    assertThat(emptyRoot.firstBrokenEntry()).isPresent();
    assertThat(emptyRoot.brokenValues()).isNotEmpty();
  }

  /** A short old root is no better than a missing one: the rows under it cannot be trusted. */
  @Test
  @DisplayName("treats an old root that is too short as one it cannot verify with")
  void treats_a_short_old_root_as_unverifiable() {
    card();
    byte[] current = "a newer root, long enough to be one".getBytes(StandardCharsets.UTF_8);
    JdbcStorage shortRoot =
        rooted(
            "r2",
            Map.of("r2", current, TestKeys.ROOT_ID, "short".getBytes(StandardCharsets.UTF_8)),
            Axes.of(TENANT, INTEGRITY, DATA));

    assertThat(shortRoot.firstBrokenEntry()).isPresent();
    assertThat(shortRoot.brokenValues()).isNotEmpty();
  }

  @Test
  @DisplayName("checking the trail reports a broken connection rather than corrupting silently")
  void first_broken_entry_reports_when_the_table_is_gone() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_audit");
    }

    assertThatThrownBy(storage::firstBrokenEntry)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read the trail back");
  }

  /**
   * The same failure the dropped-table tests exercise, but with the connection's own {@code
   * close()} failing too -- so the read failure and the close failure both have to be reported
   * without either one replacing the other.
   */
  @Test
  @DisplayName(
      "checking the trail reports the read failure even when closing the connection fails too")
  void first_broken_entry_reports_when_closing_also_fails() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_audit");
    }
    JdbcStorage overFailingClose =
        new JdbcStorageConfig()
            .dataSource(dataSourceWhoseCloseFails())
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .withoutMigration()
            .storage(Axes.of(TENANT, INTEGRITY, DATA));

    assertThatThrownBy(overFailingClose::firstBrokenEntry)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read the trail back");
  }

  @Test
  @DisplayName(
      "checking for missing values reports a broken connection rather than corrupting silently")
  void missing_values_reports_when_the_table_is_gone() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_audit");
    }

    assertThatThrownBy(storage::missingValues)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not check the values against the trail");
  }

  @Test
  @DisplayName(
      "checking for broken values reports a broken connection rather than corrupting silently")
  void broken_values_reports_when_the_table_is_gone() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_value, occlude_lineage");
    }

    assertThatThrownBy(storage::brokenValues)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read the values back");
  }

  @Test
  @DisplayName("metadata of a value nobody wrote is absent, not an exception")
  void metadata_of_an_unknown_value_is_absent() {
    assertThat(storage.metadata("does-not-exist")).isEmpty();
  }

  @Test
  @DisplayName("metadata reports a broken connection rather than throwing something unrelated")
  void metadata_reports_when_the_table_is_gone() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_value, occlude_lineage");
    }

    assertThatThrownBy(() -> storage.metadata("anything"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read anything");
  }

  @Test
  @DisplayName("metadata reports the read failure even when closing the connection fails too")
  void metadata_reports_when_closing_also_fails() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_value, occlude_lineage");
    }
    JdbcStorage overFailingClose =
        new JdbcStorageConfig()
            .dataSource(dataSourceWhoseCloseFails())
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .withoutMigration()
            .storage(Axes.of(TENANT, INTEGRITY, DATA));

    assertThatThrownBy(() -> overFailingClose.metadata("anything"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read anything");
  }

  @Test
  @DisplayName("metadata for no ids at all asks the database nothing")
  void metadata_for_no_ids_asks_nothing() {
    assertThat(storage.metadata(List.<String>of())).isEmpty();
  }

  @Test
  @DisplayName("metadata for several ids reports a derived value's lineage too")
  void metadata_for_several_ids_reports_lineage() {
    Occluded<Card> card = card();
    acme();
    Occluded<Last4> last4 = cardLast4.derive(card).orThrow();

    Map<String, StoredMetadata> found = storage.metadata(List.of(card.id(), last4.id()));

    assertThat(found.get(card.id()).lineage().asserted()).isTrue();
    assertThat(found.get(last4.id()).lineage().parents()).containsExactly(card.id());
    assertThat(found.get(last4.id()).lineage().derivation()).contains("Card.last4");
  }

  @Test
  @DisplayName(
      "metadata for several ids reports a broken connection rather than throwing something"
          + " unrelated")
  void metadata_for_several_ids_reports_when_the_table_is_gone() throws SQLException {
    card();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_value, occlude_lineage");
    }
    List<String> ids = List.of("anything");

    assertThatThrownBy(() -> storage.metadata(ids))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read");
  }

  @Test
  @DisplayName("no values are asked for, so none are decoded")
  void values_for_nothing_decodes_nothing() {
    assertThat(storage.values(Map.of())).isEmpty();
  }

  @Test
  @DisplayName(
      "reading several values reports a broken connection rather than throwing something"
          + " unrelated")
  void values_reports_when_the_table_is_gone() throws SQLException {
    Occluded<Card> card = card();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_value, occlude_lineage");
    }
    Map<String, TypeRef<?>> wanted = Map.of(card.id(), TypeRef.of(Card.class));

    assertThatThrownBy(() -> storage.values(wanted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read");
  }

  @Test
  @DisplayName("a value nobody wrote is absent, not an exception")
  void value_of_an_unknown_id_is_absent() {
    TypeRef<Card> type = TypeRef.of(Card.class);

    assertThat(storage.value("does-not-exist", type)).isEmpty();
  }

  @Test
  @DisplayName(
      "reading a single value reports a broken connection rather than throwing something"
          + " unrelated")
  void value_reports_when_the_table_is_gone() throws SQLException {
    Occluded<Card> card = card();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_value, occlude_lineage");
    }
    String id = card.id();
    TypeRef<Card> type = TypeRef.of(Card.class);

    assertThatThrownBy(() -> storage.value(id, type))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read " + id);
  }

  @Test
  @DisplayName("reading a single value reports the read failure even when closing fails too")
  void value_reports_when_closing_also_fails() throws SQLException {
    Occluded<Card> card = card();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_value, occlude_lineage");
    }
    JdbcStorage overFailingClose =
        new JdbcStorageConfig()
            .dataSource(dataSourceWhoseCloseFails())
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .withoutMigration()
            .storage(Axes.of(TENANT, INTEGRITY, DATA));
    String id = card.id();
    TypeRef<Card> type = TypeRef.of(Card.class);

    assertThatThrownBy(() -> overFailingClose.value(id, type))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read " + id);
  }

  @Test
  @DisplayName(
      "looking for a value reports a broken connection rather than throwing something unrelated")
  void contains_reports_when_the_table_is_gone() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE occlude_value, occlude_lineage");
    }

    assertThatThrownBy(() -> storage.contains("anything"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not look for anything");
  }

  @Test
  @DisplayName("a single secret roots everything, and refuses anything claiming another root")
  void a_single_secret_roots_everything() {
    Axes axes = Axes.of(TENANT, INTEGRITY, DATA);
    byte[] secret = "only secret -- long enough to be a root".getBytes(StandardCharsets.UTF_8);
    JdbcStorage single =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn("r1", secret)
            .withoutMigration()
            .storage(axes);
    single.append(aQueryLine("x"));
    assertThat(single.firstBrokenEntry()).isEmpty();

    // "r1" is the only root this configuration knows about; the same secret under another name
    // does not verify what was written under the first one.
    JdbcStorage sameSecretDifferentName =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn("r2", secret)
            .withoutMigration()
            .storage(axes);

    assertThat(sameSecretDifferentName.firstBrokenEntry()).isPresent();
  }

  @Test
  @DisplayName("plain storage writes bytes as they are, and reads them back the same way")
  void stored_plainly_reads_back_what_it_wrote() {
    JdbcStorage plain =
        new JdbcStorageConfig()
            .dataSource(dataSource)
            .codecs(new JacksonCodecFactory(JsonMapper.builder().build()))
            .encryptedWith(TestKeys.dataKeys())
            .rootedIn(TestKeys.ROOT_ID, TestKeys.root())
            .withoutMigration()
            .storage(Axes.of(TENANT, INTEGRITY, DATA));
    StoredValue value =
        new StoredValue(
            "hello there",
            OccludedType.of("note", String.class),
            Label.of(TENANT, "acme"),
            Lineage.occluded());
    plain.put(
        "note-1",
        value,
        new AuditRecord(
            AuditRecord.Operation.CONCEAL,
            "note-1",
            Optional.empty(),
            AuditRecord.Outcome.ALLOWED,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Map.of()));

    assertThat(plain.value("note-1", TypeRef.of(String.class))).contains("hello there");
  }
}
