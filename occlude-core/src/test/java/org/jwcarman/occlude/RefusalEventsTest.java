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
package org.jwcarman.occlude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.MemoryStorage;
import org.jwcarman.occlude.storage.Storage;
import org.jwcarman.occlude.storage.StorageIntegrityException;
import org.jwcarman.occlude.storage.StorageUnreadableException;
import org.jwcarman.occlude.storage.StoredMetadata;
import org.jwcarman.occlude.storage.StoredValue;

/**
 * Every refused line is also an event, for an application that must know now and not at the next
 * audit.
 *
 * <p>The label says "initech" and the access says "acme", so a test can tell the label from the
 * access context in what an event carries.
 */
@DisplayName("Refusal events")
class RefusalEventsTest {

  private static final OccludedType<String> NOTE = OccludedType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Ceiling ANYTHING = Ceiling.of(TENANT, Constraint.any());
  private static final Ceiling ONLY_GLOBEX = Ceiling.of(TENANT, Constraint.atMost("globex"));
  private static final AccessContext DANA =
      AccessContext.of(Map.of("tenant", "acme", "principal", "dana"));

  /** Honest until told otherwise. */
  private static final class Failing implements Storage {
    private final MemoryStorage delegate = new MemoryStorage();
    private RuntimeException onValue;
    private RuntimeException onAppend;
    private RuntimeException onPut;

    @Override
    public void put(String id, StoredValue value, AuditRecord entry) {
      if (onPut != null) {
        throw onPut;
      }
      delegate.put(id, value, entry);
    }

    @Override
    public void append(AuditRecord entry) {
      if (onAppend != null) {
        throw onAppend;
      }
      delegate.append(entry);
    }

    @Override
    public Optional<StoredMetadata> metadata(String id) {
      return delegate.metadata(id);
    }

    @Override
    public <T> Optional<T> value(String id, TypeRef<T> type) {
      if (onValue != null) {
        throw onValue;
      }
      return delegate.value(id, type);
    }

    @Override
    public boolean contains(String id) {
      return delegate.contains(id);
    }

    @Override
    public List<String> erase(String root, Function<String, AuditRecord> lineFor) {
      return delegate.erase(root, lineFor);
    }

    List<AuditRecord> audit() {
      return delegate.audit();
    }
  }

  private final Failing storage = new Failing();
  private final List<RefusalEvent> events = new ArrayList<>();
  private final DefaultCharter charter = new DefaultCharter(TENANT);

  private final Occlude<String> notes =
      charter.source("notes", NOTE, ctx -> Label.of(TENANT, "initech"));
  private final Occlude<String> unlabelled = charter.source("unlabelled", NOTE, ctx -> null);
  private final Reveal<String> desk = charter.sink("desk", ANYTHING, NOTE).reading(NOTE);
  private final Reveal<String> elsewhere =
      charter.sink("elsewhere", ONLY_GLOBEX, NOTE).reading(NOTE);
  private final Query<String, String> barred =
      charter.query(
          "barred", NOTE, String.class, (v, q, ctx) -> true, q -> q.accepting(ONLY_GLOBEX));
  private final Derivation<String, String> whisper =
      charter.derivation("whisper", NOTE, NOTE, String::toLowerCase, d -> d.accepting(ONLY_GLOBEX));
  private final Derivation<String, String> shout =
      charter.derivation("shout", NOTE, NOTE, String::toUpperCase, d -> d.accepting(ANYTHING));

  /** Fails with the value in its message, the way an application's own validation often does. */
  private final Derivation<String, String> echo =
      charter.derivation(
          "echo",
          NOTE,
          NOTE,
          value -> {
            throw new IllegalArgumentException("cannot echo " + value);
          },
          d -> d.accepting(ANYTHING));

  private final Inspection outsider = charter.inspection("outsider", ONLY_GLOBEX);
  private final Erasure never = charter.erasure("never", (label, ctx) -> false);

  private void bindWith(RefusalListener listener) {
    charter.bind(Bindings.of(storage).withIdentity(() -> DANA).onRefusal(listener));
  }

  @Nested
  @DisplayName("with a listener on the bindings")
  class WithAListener {

    {
      bindWith(events::add);
    }

    @Test
    @DisplayName("hears every kind of refusal, with what the line says")
    void hears_every_kind_of_refusal() {
      Occluded<String> held = notes.occlude("hello");

      elsewhere.reveal(held);
      barred.ask(held, "x");
      whisper.derive(held);
      outsider.inspect(held);
      never.erase(held);

      assertThat(events)
          .extracting(event -> event.operation() + " " + event.portal() + " " + event.reason())
          .containsExactly(
              "REVEAL elsewhere ABOVE_CEILING",
              "QUERY barred ABOVE_CEILING",
              "DERIVE whisper ABOVE_CEILING",
              "INSPECT outsider ABOVE_CEILING",
              "ERASE never NOT_PERMITTED");
      assertThat(events)
          .allSatisfy(
              event -> {
                assertThat(event.valueId()).isEqualTo(held.id());
                assertThat(event.context()).isEqualTo(DANA);
                assertThat(event.at()).isNotNull();
              });
    }

    @Test
    @DisplayName("hears a refusal to occlude, which throws rather than returns")
    void hears_a_refusal_to_occlude() {
      assertThatThrownBy(() -> unlabelled.occlude("hello")).isInstanceOf(RefusedException.class);

      assertThat(events)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.operation()).isEqualTo(AuditRecord.Operation.CONCEAL);
                assertThat(event.portal()).isEqualTo("unlabelled");
              });
    }

    @Test
    @DisplayName("hears a function that failed as FAILED")
    void hears_a_function_that_failed() {
      Occluded<String> held = notes.occlude("hello");

      echo.derive(held);

      assertThat(events)
          .singleElement()
          .extracting(RefusalEvent::reason)
          .isEqualTo(RefusalReason.FAILED);
    }

    @Test
    @DisplayName("hears what storage found, as the line records it")
    void hears_what_storage_found() {
      Occluded<String> held = notes.occlude("hello");

      storage.onValue = new StorageIntegrityException("the payload stored for " + held.id());
      assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(StorageIntegrityException.class);
      storage.onValue = new StorageUnreadableException("would not decrypt " + held.id(), null);
      assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(StorageUnreadableException.class);

      assertThat(events)
          .extracting(RefusalEvent::reason)
          .containsExactly(RefusalReason.NOT_AS_SIGNED, RefusalReason.UNREADABLE);
    }

    @Test
    @DisplayName("hears nothing of an operation that was allowed")
    void hears_nothing_allowed() {
      Occluded<String> held = notes.occlude("hello");

      desk.reveal(held);
      shout.derive(held);

      assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("hears nothing when the line itself could not be written")
    void hears_nothing_unrecorded() {
      Occluded<String> held = notes.occlude("hello");
      storage.onAppend = new IllegalStateException("the database is down");

      assertThatThrownBy(() -> elsewhere.reveal(held)).isInstanceOf(IllegalStateException.class);

      assertThat(events).isEmpty();
    }

    /** The rule the event rests on: who asked and about what, never what the record protects. */
    @Test
    @DisplayName("never carries the value, the label, the detail or an exception's message")
    void never_carries_what_the_record_protects() {
      Occluded<String> held = notes.occlude("hello");

      elsewhere.reveal(held);
      echo.derive(held);

      assertThat(events).hasSize(2);
      assertThat(events)
          .allSatisfy(
              event ->
                  assertThat(event.toString())
                      .doesNotContain("hello")
                      .doesNotContain("initech")
                      .doesNotContain("cannot echo")
                      .doesNotContain(IllegalArgumentException.class.getName())
                      .contains("dana"));
    }
  }

  /**
   * Each of these once gave prose as its reason. The record promises a code in the clear, so an
   * alert can match on it without decrypting anything.
   */
  @Nested
  @DisplayName("gives a code, in the event and in the line")
  class GivesACode {

    {
      bindWith(events::add);
    }

    private void assertCode(RefusalReason reason) {
      assertThat(events).last().extracting(RefusalEvent::reason).isEqualTo(reason);
      assertThat(storage.audit().getLast().reason()).contains(reason.name());
    }

    @Test
    @DisplayName("for an erasure of a value nobody is holding")
    void for_an_erasure_of_nothing() {
      never.erase(Occluded.of("occ_never-minted"));

      assertCode(RefusalReason.NO_SUCH_VALUE);
    }

    @Test
    @DisplayName("for an erasure its policy did not permit")
    void for_an_erasure_not_permitted() {
      never.erase(notes.occlude("hello"));

      assertCode(RefusalReason.NOT_PERMITTED);
    }

    @Test
    @DisplayName("for a source that could not label what arrived")
    void for_a_source_that_could_not_label() {
      assertThatThrownBy(() -> unlabelled.occlude("hello")).isInstanceOf(RefusedException.class);

      assertCode(RefusalReason.SOURCE_CANNOT_LABEL);
    }

    @Test
    @DisplayName("for a derivation whose result could not be written")
    void for_a_derivation_that_could_not_be_written() {
      Occluded<String> held = notes.occlude("hello");
      storage.onPut = new IllegalStateException("this store is no longer accepting writes");

      shout.derive(held);

      assertCode(RefusalReason.NO_SUCH_VALUE);
    }
  }

  @Test
  @DisplayName("gives a code for a label that leaves a required axis unsaid")
  void gives_a_code_for_an_incomplete_label() {
    Axis<String> required = Axis.matching("tenant").required();
    DefaultCharter strict = new DefaultCharter(required);
    Occlude<String> vague = strict.source("vague", NOTE, ctx -> Label.nothing());
    strict.bind(Bindings.of(storage).withIdentity(() -> DANA).onRefusal(events::add));

    assertThatThrownBy(() -> vague.occlude("hello")).isInstanceOf(RefusedException.class);

    assertThat(events)
        .singleElement()
        .extracting(RefusalEvent::reason)
        .isEqualTo(RefusalReason.INCOMPLETE_LABEL);
    assertThat(storage.audit().getLast().reason()).contains("INCOMPLETE_LABEL");
  }

  @Test
  @DisplayName("tells the listener only once the line is written")
  void tells_once_the_line_is_written() {
    AtomicReference<List<AuditRecord>> seen = new AtomicReference<>();
    bindWith(event -> seen.set(List.copyOf(storage.audit())));
    Occluded<String> held = notes.occlude("hello");

    elsewhere.reveal(held);

    assertThat(seen.get())
        .last()
        .satisfies(
            line -> {
              assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
              assertThat(line.target()).contains("elsewhere");
            });
  }

  @Test
  @DisplayName("is unmoved by a listener that throws")
  void is_unmoved_by_a_listener_that_throws() {
    bindWith(
        event -> {
          throw new IllegalStateException("the pager is down");
        });
    Occluded<String> held = notes.occlude("hello");

    Revealed<String> result = elsewhere.reveal(held);

    assertThat(result.succeeded()).isFalse();
    assertThat(storage.audit().getLast().outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
  }

  @Test
  @DisplayName("keeps the listener when observed afterwards")
  void keeps_the_listener_when_observed_afterwards() {
    charter.bind(
        Bindings.of(storage)
            .withIdentity(() -> DANA)
            .onRefusal(events::add)
            .observedBy(ObservationRegistry.create()));
    Occluded<String> held = notes.occlude("hello");

    elsewhere.reveal(held);

    assertThat(events).hasSize(1);
  }

  @Test
  @DisplayName("refuses no listener at all")
  void refuses_no_listener() {
    Bindings bindings = Bindings.of(new MemoryStorage()).withoutIdentity();

    assertThatThrownBy(() -> bindings.onRefusal(null)).isInstanceOf(NullPointerException.class);
  }

  @Nested
  @DisplayName("asynchronously")
  class Asynchronously {

    @Test
    @DisplayName("delivers on the executor's thread")
    void delivers_on_the_executors_thread() throws InterruptedException {
      CountDownLatch heard = new CountDownLatch(1);
      AtomicReference<String> thread = new AtomicReference<>();
      try (ExecutorService executor =
          Executors.newSingleThreadExecutor(r -> new Thread(r, "refusal-listener"))) {
        RefusalListener listener =
            event -> {
              thread.set(Thread.currentThread().getName());
              heard.countDown();
            };
        bindWith(listener.async(executor));
        Occluded<String> held = notes.occlude("hello");

        elsewhere.reveal(held);

        assertThat(heard.await(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(thread.get()).isEqualTo("refusal-listener");
    }

    @Test
    @DisplayName("is unmoved by an executor that was closed")
    void is_unmoved_by_a_closed_executor() {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      executor.close();
      RefusalListener listener = events::add;
      bindWith(listener.async(executor));
      Occluded<String> held = notes.occlude("hello");

      Revealed<String> result = elsewhere.reveal(held);

      assertThat(result.succeeded()).isFalse();
      assertThat(storage.audit().getLast().outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
      assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("refuses no executor at all")
    void refuses_no_executor() {
      RefusalListener listener = events::add;

      assertThatThrownBy(() -> listener.async(null)).isInstanceOf(NullPointerException.class);
    }
  }
}
