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

import io.micrometer.common.KeyValue;
import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.observation.OccludeObservationContext;
import org.jwcarman.occlude.observation.OccludeObservationDocumentation;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.MemoryStorage;
import org.jwcarman.occlude.storage.Storage;
import org.jwcarman.occlude.storage.StorageIntegrityException;
import org.jwcarman.occlude.storage.StorageUnreadableException;
import org.jwcarman.occlude.storage.StoredMetadata;
import org.jwcarman.occlude.storage.StoredValue;

/**
 * Every operation is one observation, tagged with what describes the system and nothing that
 * describes the data.
 */
@DisplayName("Observing operations")
class ObservedOperationsTest {

  private static final OccludedType<String> NOTE = OccludedType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Ceiling ANYTHING = Ceiling.of(TENANT, Constraint.any());
  private static final Ceiling ONLY_GLOBEX = Ceiling.of(TENANT, Constraint.atMost("globex"));

  /** Honest until told otherwise. */
  private static final class Failing implements Storage {
    private final MemoryStorage delegate = new MemoryStorage();
    private RuntimeException onValue;
    private RuntimeException onAppend;

    @Override
    public void put(String id, StoredValue value, AuditRecord entry) {
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
  }

  private final List<OccludeObservationContext> observed = new ArrayList<>();
  private final ObservationRegistry registry = ObservationRegistry.create();
  private final Failing storage = new Failing();
  private final DefaultCharter charter = new DefaultCharter(TENANT);

  private final Occlude<String> notes =
      charter.source("notes", NOTE, ctx -> Label.of(TENANT, ctx.get("tenant").orElseThrow()));
  private final Occlude<String> unlabelled = charter.source("unlabelled", NOTE, ctx -> null);
  private final Reveal<String> desk = charter.sink("desk", ANYTHING, NOTE).reading(NOTE);
  private final Reveal<String> elsewhere =
      charter.sink("elsewhere", ONLY_GLOBEX, NOTE).reading(NOTE);
  private final Query<String, String> mentions =
      charter.query(
          "mentions", NOTE, String.class, (v, q, ctx) -> v.contains(q), q -> q.accepting(ANYTHING));
  private final Query<String, String> barred =
      charter.query(
          "barred", NOTE, String.class, (v, q, ctx) -> true, q -> q.accepting(ONLY_GLOBEX));
  private final Derivation<String, String> shout =
      charter.derivation("shout", NOTE, NOTE, String::toUpperCase, d -> d.accepting(ANYTHING));
  private final Derivation<String, String> whisper =
      charter.derivation("whisper", NOTE, NOTE, String::toLowerCase, d -> d.accepting(ONLY_GLOBEX));
  private final Fold<String, String> join =
      charter.fold("join", NOTE, NOTE, all -> String.join(" ", all), d -> d.accepting(ANYTHING));
  private final Inspection inspector = charter.inspection("inspector", ANYTHING);
  private final Inspection outsider = charter.inspection("outsider", ONLY_GLOBEX);
  private final Erasure erasure = charter.erasure("erasure", (label, ctx) -> true);
  private final Erasure never = charter.erasure("never", (label, ctx) -> false);

  {
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return context instanceof OccludeObservationContext;
              }

              @Override
              public void onStop(Observation.Context context) {
                observed.add((OccludeObservationContext) context);
              }
            });
    charter.bind(
        Bindings.of(storage)
            .withIdentity(() -> AccessContext.of(Map.of("tenant", "acme", "principal", "dana")))
            .observedBy(registry));
  }

  private static Map<String, String> tags(OccludeObservationContext context) {
    Map<String, String> tags = new LinkedHashMap<>();
    for (KeyValue keyValue : context.getLowCardinalityKeyValues()) {
      tags.put(keyValue.getKey(), keyValue.getValue());
    }
    return tags;
  }

  private OccludeObservationContext last() {
    return observed.getLast();
  }

  @Test
  @DisplayName("records one observation per operation, at the portal that performed it")
  void records_one_per_operation() {
    Occluded<String> held = notes.occlude("hello");
    desk.reveal(held);
    mentions.ask(held, "ell");
    Occluded<String> loud = shout.derive(held).orThrow();
    join.fold(List.of(held, loud));
    inspector.inspect(held);
    erasure.erase(held);

    assertThat(observed)
        .extracting(context -> tags(context).get("operation") + " " + context.getPortal())
        .containsExactly(
            "conceal notes",
            "reveal desk",
            "query mentions",
            "derive shout",
            "derive join",
            "inspect inspector",
            "erase erasure");
    assertThat(observed)
        .allSatisfy(
            context ->
                assertThat(tags(context))
                    .containsEntry("outcome", "allowed")
                    .containsEntry("reason", "none")
                    .containsEntry("error", "none"));
    assertThat(last().getName()).isEqualTo("occlude.operation");
    assertThat(last().getContextualName()).isEqualTo("occlude erase erasure");
  }

  @Test
  @DisplayName("records every kind of refusal with its reason")
  void records_refusals() {
    Occluded<String> held = notes.occlude("hello");

    elsewhere.reveal(held);
    assertThat(tags(last()))
        .containsEntry("outcome", "refused")
        .containsEntry("reason", "ABOVE_CEILING");
    barred.ask(held, "x");
    assertThat(tags(last())).containsEntry("reason", "ABOVE_CEILING");
    whisper.derive(held);
    assertThat(tags(last())).containsEntry("reason", "ABOVE_CEILING");
    outsider.inspect(held);
    assertThat(tags(last())).containsEntry("reason", "ABOVE_CEILING");
    never.erase(held);
    assertThat(tags(last())).containsEntry("reason", "NOT_PERMITTED");
    assertThatThrownBy(() -> unlabelled.occlude("x")).isInstanceOf(RefusedException.class);
    assertThat(tags(last()))
        .containsEntry("operation", "conceal")
        .containsEntry("outcome", "refused")
        .containsEntry("reason", "SOURCE_CANNOT_LABEL")
        .containsEntry("error", "none");
  }

  @Test
  @DisplayName("records what storage found as a refusal, with the class of what it threw")
  void records_storage_findings() {
    Occluded<String> held = notes.occlude("hello");

    storage.onValue =
        new StorageIntegrityException("the payload stored for " + held.id() + " is altered");
    assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(StorageIntegrityException.class);
    assertThat(tags(last()))
        .containsEntry("outcome", "refused")
        .containsEntry("reason", "NOT_AS_SIGNED")
        .containsEntry("error", "StorageIntegrityException");

    storage.onValue = new StorageUnreadableException("would not decrypt " + held.id(), null);
    assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(StorageUnreadableException.class);
    assertThat(tags(last())).containsEntry("reason", "UNREADABLE");
  }

  @Test
  @DisplayName("records what is not a decision as a failure")
  void records_failures() {
    Occluded<String> held = notes.occlude("hello");
    storage.onAppend = new IllegalStateException("the database is down");

    assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(IllegalStateException.class);

    assertThat(tags(last()))
        .containsEntry("outcome", "failed")
        .containsEntry("reason", "none")
        .containsEntry("error", "IllegalStateException");
  }

  /** The rule the whole design rests on, as a test a later change cannot quietly break. */
  @Test
  @DisplayName("never carries a value, an identifier, a label, an identity or an exception")
  void never_carries_what_the_trail_protects() {
    Occluded<String> held = notes.occlude("hello");
    desk.reveal(held);
    elsewhere.reveal(held);
    storage.onValue =
        new StorageIntegrityException("the payload stored for " + held.id() + " is altered");
    assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(StorageIntegrityException.class);

    assertThat(observed).hasSize(4);
    for (OccludeObservationContext context : observed) {
      String everything =
          context.getName()
              + context.getContextualName()
              + StreamSupport.stream(context.getAllKeyValues().spliterator(), false).toList();
      assertThat(everything)
          .doesNotContain(held.id())
          .doesNotContain("hello")
          .doesNotContain("acme")
          .doesNotContain("dana");
      assertThat(context.getError()).isNull();
    }
  }

  @Test
  @DisplayName("carries exactly the keys it documents")
  void carries_exactly_the_documented_keys() {
    notes.occlude("hello");

    assertThat(tags(last()).keySet())
        .containsExactlyInAnyOrder(
            Arrays.stream(OccludeObservationDocumentation.OPERATION.getLowCardinalityKeyNames())
                .map(KeyName::asString)
                .toArray(String[]::new));
  }

  @Test
  @DisplayName("ends an operation exactly as it would have when a handler throws")
  void is_unmoved_by_a_handler_that_throws() {
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStart(Observation.Context context) {
                throw new IllegalStateException("the collector is down");
              }

              @Override
              public void onScopeOpened(Observation.Context context) {
                throw new IllegalStateException("the collector is down");
              }

              @Override
              public void onStop(Observation.Context context) {
                throw new IllegalStateException("the collector is down");
              }
            });

    Occluded<String> held = notes.occlude("hello");

    assertThat(desk.reveal(held).allowed()).isTrue();
  }

  @Test
  @DisplayName("refuses to be observed by no registry at all")
  void refuses_no_registry() {
    Bindings bindings = Bindings.of(new MemoryStorage()).withoutIdentity();

    assertThatThrownBy(() -> bindings.observedBy(null)).isInstanceOf(NullPointerException.class);
  }
}
