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

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;

/**
 * Reading what a value is labelled and where it came from.
 *
 * <p>These were once charter methods that answered anyone, with no line in the record. A label
 * names a tenant; storage encrypts it and a refusal will not repeat it, so reading one is checked
 * against a ceiling and recorded like every other access.
 */
@DisplayName("An inspection")
class InspectionTest {

  private static final OccludedType<String> NOTE = OccludedType.of(String.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  private final AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());
  private final MemoryStorage storage = new MemoryStorage();
  private final DefaultCharter charter = new DefaultCharter(TENANT);

  private final Occlude<String> notes =
      charter.source("notes", NOTE, ctx -> Label.of(TENANT, ctx.get("tenant").orElse("acme")));

  private final Derivation<String, String> upper =
      charter.derivation(
          "upper",
          NOTE,
          NOTE,
          String::toUpperCase,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

  /** A support desk sees the labels of its own tenant's values, and nobody else's. */
  private final Inspection desk =
      charter.inspection(
          "desk", ctx -> Ceiling.of(TENANT, Constraint.atMost(ctx.get("tenant").orElse("nobody"))));

  private final Inspection broken =
      charter.inspection(
          "broken",
          ctx -> {
            throw new IllegalStateException("cannot decide");
          });

  {
    charter.bind(storage, edge::get);
  }

  @Test
  @DisplayName("hands back the label and lineage of a value its ceiling admits, and records it")
  void hands_back_what_its_ceiling_admits() {
    edge.set(AccessContext.of("tenant", "acme"));
    Occluded<String> note = notes.occlude("hello");
    Occluded<String> shouted = upper.derive(note).orThrow();

    Inspected.Seen seen = desk.inspect(shouted).orThrow();

    assertThat(seen.label()).isEqualTo(Label.of(TENANT, "acme"));
    assertThat(seen.lineage().parents()).containsExactly(note.id());
    assertThat(storage.audit(AuditRecord.Operation.INSPECT))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.ALLOWED);
              assertThat(line.target()).contains("desk");
              assertThat(line.value()).isEqualTo(shouted.id());
            });
  }

  @Test
  @DisplayName("refuses a value above its ceiling without saying what it is labelled")
  void refuses_above_its_ceiling() {
    edge.set(AccessContext.of("tenant", "globex"));
    Occluded<String> globexNote = notes.occlude("theirs");
    edge.set(AccessContext.of("tenant", "acme"));

    Inspected inspected = desk.inspect(globexNote);

    assertThat(inspected)
        .isInstanceOfSatisfying(
            Inspected.Refused.class,
            refused -> {
              assertThat(refused.reason()).isEqualTo(Inspected.Reason.ABOVE_CEILING);
              assertThat(refused.detail()).doesNotContain("globex");
            });
    assertThat(inspected.seen()).isEmpty();
    assertThat(storage.audit(AuditRecord.Operation.INSPECT))
        .singleElement()
        .satisfies(line -> assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }

  /** A ceiling is application code, and one that cannot decide has not said yes. */
  @Test
  @DisplayName("refuses when its ceiling throws, rather than propagating the crash")
  void refuses_when_its_ceiling_throws() {
    Occluded<String> note = notes.occlude("hello");

    Inspected inspected = broken.inspect(note);

    assertThat(inspected.succeeded()).isFalse();
    assertThatThrownBy(inspected::orThrow)
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("ABOVE_CEILING");
  }

  @Test
  @DisplayName("cannot be used before its charter is bound")
  void cannot_be_used_before_binding() {
    DefaultCharter unbound = new DefaultCharter(TENANT);
    Inspection early = unbound.inspection("early", Ceiling.of(TENANT, Constraint.any()));
    Occluded<String> anything = Occluded.of("occ_anything");

    assertThatThrownBy(() -> early.inspect(anything))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("before its charter is bound");
  }

  @Test
  @DisplayName("is listed in the manifest with what it accepts for the rendered access")
  void is_listed_in_the_manifest() {
    Manifest manifest = charter.manifest(AccessContext.of("tenant", "acme"));

    assertThat(manifest.inspections())
        .extracting(Manifest.Entry::name)
        .containsExactly("desk", "broken");
    assertThat(manifest.inspections().getFirst().detail()).contains("acme");
  }
}
