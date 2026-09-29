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

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
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
 * A store that found what it holds is not what it signed.
 *
 * <p>The store refuses by throwing, and that must not slip past the record: every operation writes
 * a refused line saying so before the exception carries on to the caller.
 */
@DisplayName("A store that finds tampering on read")
class IntegrityFailureTest {

  private static final OccludedType<String> NOTE = OccludedType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Ceiling ANYTHING = Ceiling.of(TENANT, Constraint.any());

  /** Honest until told otherwise, then refuses reads the way a durable store refuses tampering. */
  private static final class Tampered implements Storage {
    private final MemoryStorage delegate = new MemoryStorage();
    private boolean metadataAltered;
    private boolean valuesAltered;
    private boolean appendsFail;
    private boolean valuesUnreadable;

    @Override
    public void put(String id, StoredValue value, AuditRecord entry) {
      delegate.put(id, value, entry);
    }

    @Override
    public void append(AuditRecord entry) {
      if (appendsFail) {
        throw new IllegalStateException("the trail is unavailable");
      }
      delegate.append(entry);
    }

    @Override
    public Optional<StoredMetadata> metadata(String id) {
      if (metadataAltered) {
        throw new StorageIntegrityException("the label stored for " + id + " is not as signed");
      }
      return delegate.metadata(id);
    }

    @Override
    public <T> Optional<T> value(String id, TypeRef<T> type) {
      if (valuesUnreadable) {
        throw new StorageUnreadableException(
            "the payload stored for " + id + " would not decrypt", null);
      }
      if (valuesAltered) {
        throw new StorageIntegrityException("the payload stored for " + id + " is not as signed");
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

  private final Tampered storage = new Tampered();
  private final DefaultCharter charter = new DefaultCharter(TENANT);
  private final Occlude<String> notes = charter.source("notes", NOTE, Label.of(TENANT, "acme"));
  private final Reveal<String> desk = charter.sink("desk", ANYTHING, NOTE).reading(NOTE);
  private final Query<String, String> mentions =
      charter.query(
          "mentions", NOTE, String.class, (v, q, ctx) -> v.contains(q), q -> q.accepting(ANYTHING));
  private final Derivation<String, String> shout =
      charter.derivation("shout", NOTE, NOTE, String::toUpperCase, d -> d.accepting(ANYTHING));
  private final Inspection inspector = charter.inspection("inspector", ANYTHING);
  private final Erasure erasure = charter.erasure("erasure", (label, ctx) -> true);
  private final Occluded<String> held;

  {
    charter.bind(storage, AccessContextProvider.none());
    held = notes.occlude("hello");
  }

  private void assertRecorded(AuditRecord.Operation operation) {
    assertThat(storage.delegate.audit(operation))
        .anySatisfy(
            line -> {
              assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED);
              assertThat(line.reason()).contains("NOT_AS_SIGNED");
            });
  }

  @Test
  @DisplayName("a reveal records it, whether the label or the value was altered")
  void a_reveal_records_it() {
    storage.valuesAltered = true;
    assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(StorageIntegrityException.class);
    storage.valuesAltered = false;
    storage.metadataAltered = true;
    assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(StorageIntegrityException.class);

    assertThat(storage.delegate.audit(AuditRecord.Operation.REVEAL))
        .filteredOn(line -> line.reason().filter("NOT_AS_SIGNED"::equals).isPresent())
        .hasSize(2);
  }

  @Test
  @DisplayName("a question records it, whether the label or the value was altered")
  void a_question_records_it() {
    storage.valuesAltered = true;
    assertThatThrownBy(() -> mentions.ask(held, "hell"))
        .isInstanceOf(StorageIntegrityException.class);
    storage.valuesAltered = false;
    storage.metadataAltered = true;
    assertThatThrownBy(() -> mentions.ask(held, "hell"))
        .isInstanceOf(StorageIntegrityException.class);

    assertRecorded(AuditRecord.Operation.QUERY);
  }

  @Test
  @DisplayName("a derivation records it, whether the label or the value was altered")
  void a_derivation_records_it() {
    storage.valuesAltered = true;
    assertThatThrownBy(() -> shout.derive(held)).isInstanceOf(StorageIntegrityException.class);
    storage.valuesAltered = false;
    storage.metadataAltered = true;
    assertThatThrownBy(() -> shout.derive(held)).isInstanceOf(StorageIntegrityException.class);

    assertRecorded(AuditRecord.Operation.DERIVE);
  }

  @Test
  @DisplayName("an inspection records it")
  void an_inspection_records_it() {
    storage.metadataAltered = true;

    assertThatThrownBy(() -> inspector.inspect(held)).isInstanceOf(StorageIntegrityException.class);
    assertRecorded(AuditRecord.Operation.INSPECT);
  }

  /** The finding is what matters; a record that could not be written must not replace it. */
  @Test
  @DisplayName("keeps the finding when the record of it cannot be written")
  void keeps_the_finding_when_the_record_fails() {
    storage.valuesAltered = true;
    storage.appendsFail = true;

    assertThatThrownBy(() -> mentions.ask(held, "hell"))
        .isInstanceOf(StorageIntegrityException.class)
        .satisfies(
            thrown ->
                assertThat(thrown.getSuppressed())
                    .singleElement()
                    .satisfies(
                        suppressed ->
                            assertThat(suppressed).hasMessage("the trail is unavailable")));
  }

  /** Unreadable is its own finding, recorded as such. */
  @Test
  @DisplayName("a read that will not decrypt is recorded as unreadable")
  void an_unreadable_read_is_recorded() {
    storage.valuesUnreadable = true;

    assertThatThrownBy(() -> desk.reveal(held)).isInstanceOf(StorageUnreadableException.class);
    assertThat(storage.delegate.audit(AuditRecord.Operation.REVEAL))
        .anySatisfy(line -> assertThat(line.reason()).contains("UNREADABLE"));
  }

  @Test
  @DisplayName("an erasure records it")
  void an_erasure_records_it() {
    storage.metadataAltered = true;

    assertThatThrownBy(() -> erasure.erase(held)).isInstanceOf(StorageIntegrityException.class);
    assertRecorded(AuditRecord.Operation.ERASE);
  }
}
