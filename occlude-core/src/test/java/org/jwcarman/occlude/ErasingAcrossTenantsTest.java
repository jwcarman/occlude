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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.manifest.Manifest;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.MemoryStorage;

/**
 * Erasing somebody else's data.
 *
 * <p>Erasure is the one operation a label cannot decide on its own, because destroying a value is
 * not reading it: a compliance officer is expected to remove data they were never entitled to look
 * at. So the policy sees both what is being erased and who is asking, and this is the test that it
 * really sees both. It was written after a version that saw only the who, under which acme's
 * compliance officer could erase globex's records.
 */
@DisplayName("Erasing somebody else's data")
class ErasingAcrossTenantsTest {

  private static final OccludedType<Record> RECORD_TYPE = OccludedType.of(Record.class);

  enum Level {
    LOW,
    HIGH
  }

  record Record(String text) {}

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Level> LEVEL = Axis.ladder("level", Level.LOW, Level.HIGH);

  @Test
  @DisplayName("is refused, even for a compliance officer")
  void is_refused_even_for_a_compliance_officer() {
    AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    Erasure compliance =
        config.erasure(
            "compliance",
            (label, ctx) ->
                ctx.has("role", "compliance")
                    && ctx.get("tenant")
                        .map(
                            tenant ->
                                Ceiling.of(TENANT, Constraint.atMost(tenant))
                                    .with(LEVEL, Constraint.any())
                                    .permits(label))
                        .orElse(false));

    Occlude<Record> globexRecords =
        config.source(
            "globex-records",
            RECORD_TYPE,
            ctx -> Label.of(TENANT, "globex").with(LEVEL, Level.HIGH));

    MemoryStorage storage = new MemoryStorage();
    config.bind(Bindings.of(storage).withIdentity(edge::get));

    Occluded<Record> globexRecord = globexRecords.occlude(new Record("globex's records"));

    edge.set(AccessContext.of(Map.of("tenant", "acme", "role", "compliance")));

    assertThat(compliance.erase(globexRecord))
        .isInstanceOfSatisfying(
            Erased.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(Erased.Reason.NOT_PERMITTED));
    assertThat(storage.contains(globexRecord.id())).isTrue();
  }

  /**
   * Asking to destroy something that is not here is still an attempt.
   *
   * <p>A trail recording only the attempts that found something cannot show a probe, which looks
   * exactly like this, repeatedly. Every other operation writes a line whether or not the value was
   * there; this one used to return zero in silence.
   */
  @Test
  @DisplayName("records an attempt to erase a value that is not here")
  void records_an_attempt_to_erase_what_is_not_here() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    Erasure erasure = config.erasure("anything", (label, ctx) -> true);
    config.bind(Bindings.of(storage).withoutIdentity());

    assertThat(erasure.erase(Occluded.of("occ_never-existed")))
        .isInstanceOfSatisfying(
            Erased.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(Erased.Reason.NO_SUCH_VALUE));

    assertThat(storage.audit(AuditRecord.Operation.ERASE))
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
  }

  /**
   * Removes a root and everything derived from it, however deeply, and writes one line per value
   * removed -- the trail being the only thing afterwards that can say a value ever existed.
   */
  @Test
  @DisplayName("removes a root and everything derived from it, one line per value")
  void removes_a_root_and_everything_derived_from_it() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    Erasure erasure = config.erasure("anything", (label, ctx) -> true);
    Occlude<Record> records =
        config.source(
            "records", RECORD_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.LOW));
    Derivation<Record, Record> copy =
        config.derivation(
            "copy",
            RECORD_TYPE,
            RECORD_TYPE,
            r -> new Record(r.text() + "-copy"),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any()).with(LEVEL, Constraint.any())));
    config.bind(Bindings.of(storage).withoutIdentity());

    Occluded<Record> root = records.occlude(new Record("root"));
    Occluded<Record> child = copy.derive(root).orThrow();

    int removed = erasure.erase(root).orThrow();

    assertThat(removed).isEqualTo(2);
    assertThat(storage.contains(root.id())).isFalse();
    assertThat(storage.contains(child.id())).isFalse();
    assertThat(storage.audit(AuditRecord.Operation.ERASE))
        .hasSize(2)
        .allSatisfy(line -> assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.ALLOWED));
  }

  /**
   * A value reached by more than one path from the root -- a fold combining two of the root's own
   * children -- must still be removed exactly once, not once per path that finds it.
   */
  @Test
  @DisplayName("removes a value reached by two different paths from the root only once")
  void removes_a_value_reached_by_two_paths_only_once() {
    record Branch(String tag) {}
    record Combined(String tag) {}
    OccludedType<Branch> branchType = OccludedType.of(Branch.class);
    OccludedType<Combined> combinedType = OccludedType.of(Combined.class);

    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    Erasure erasure = config.erasure("anything", (label, ctx) -> true);
    Occlude<Record> records =
        config.source(
            "records", RECORD_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.LOW));
    Ceiling anything = Ceiling.of(TENANT, Constraint.any()).with(LEVEL, Constraint.any());
    Derivation<Record, Branch> left =
        config.derivation(
            "left", RECORD_TYPE, branchType, r -> new Branch("left"), d -> d.accepting(anything));
    Derivation<Record, Branch> right =
        config.derivation(
            "right", RECORD_TYPE, branchType, r -> new Branch("right"), d -> d.accepting(anything));
    Fold<Branch, Combined> combine =
        config.fold(
            "combine",
            branchType,
            combinedType,
            branches -> new Combined(branches.size() + " branches"),
            d -> d.accepting(anything));
    config.bind(Bindings.of(storage).withoutIdentity());

    Occluded<Record> root = records.occlude(new Record("root"));
    Occluded<Branch> leftChild = left.derive(root).orThrow();
    Occluded<Branch> rightChild = right.derive(root).orThrow();
    Occluded<Combined> combined = combine.fold(List.of(leftChild, rightChild)).orThrow();

    int removed = erasure.erase(root).orThrow();

    assertThat(removed).isEqualTo(4);
    assertThat(storage.contains(combined.id())).isFalse();
    assertThat(storage.audit(AuditRecord.Operation.ERASE)).hasSize(4);
  }

  /** A policy is application code, and a policy that cannot decide has not said yes. */
  @Test
  @DisplayName("refuses when the erasure policy itself throws, rather than propagating the crash")
  void refuses_when_the_erasure_policy_throws() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    Erasure erasure =
        config.erasure(
            "undecided",
            (label, ctx) -> {
              throw new IllegalStateException("cannot decide");
            });
    Occlude<Record> records =
        config.source(
            "records", RECORD_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.LOW));
    config.bind(Bindings.of(storage).withoutIdentity());

    Occluded<Record> root = records.occlude(new Record("root"));

    assertThat(erasure.erase(root).succeeded()).isFalse();
    assertThat(storage.contains(root.id())).isTrue();
  }

  /** Several may be declared, and the record says whose policy said no. */
  @Test
  @DisplayName("names the erasure that refused, when several are declared")
  void names_the_erasure_that_refused() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    Occlude<Record> records =
        config.source(
            "records", RECORD_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.HIGH));
    Erasure retention = config.erasure("retention", (label, ctx) -> label.says(LEVEL, Level.LOW));
    Erasure compliance = config.erasure("compliance", (label, ctx) -> true);
    config.bind(Bindings.of(storage).withoutIdentity());

    Occluded<Record> root = records.occlude(new Record("kept for now"));

    assertThat(retention.erase(root).succeeded()).isFalse();
    assertThat(storage.audit(AuditRecord.Operation.ERASE))
        .singleElement()
        .satisfies(line -> assertThat(line.target()).contains("retention"));

    assertThat(compliance.erase(root).orThrow()).isEqualTo(1);
    assertThat(storage.contains(root.id())).isFalse();
  }

  /** An auditor has to be able to say whose data an erasure was about, refused or allowed. */
  @Test
  @DisplayName("records the label of what it was asked to erase")
  void records_the_label_of_what_it_was_asked_to_erase() {
    MemoryStorage storage = new MemoryStorage();
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    Occlude<Record> records =
        config.source(
            "records", RECORD_TYPE, ctx -> Label.of(TENANT, "acme").with(LEVEL, Level.LOW));
    Derivation<Record, Record> copy =
        config.derivation(
            "copy",
            RECORD_TYPE,
            RECORD_TYPE,
            r -> new Record(r.text() + "-copy"),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any()).with(LEVEL, Constraint.any())));
    Erasure nobody = config.erasure("nobody", (label, ctx) -> false);
    Erasure anybody = config.erasure("anybody", (label, ctx) -> true);
    config.bind(Bindings.of(storage).withoutIdentity());
    Occluded<Record> root = records.occlude(new Record("root"));
    Occluded<Record> child = copy.derive(root).orThrow();

    nobody.erase(root);
    anybody.erase(root);

    String label = Label.of(TENANT, "acme").with(LEVEL, Level.LOW).toString();
    assertThat(storage.audit(AuditRecord.Operation.ERASE))
        .filteredOn(line -> line.value().equals(root.id()))
        .hasSize(2)
        .allSatisfy(line -> assertThat(line.label()).contains(label));
    // A descendant's label is not in hand when it goes, and is not guessed.
    assertThat(storage.audit(AuditRecord.Operation.ERASE))
        .filteredOn(line -> line.value().equals(child.id()))
        .singleElement()
        .satisfies(line -> assertThat(line.label()).isEmpty());
  }

  @Test
  @DisplayName("refuses two erasures under one name")
  void refuses_two_erasures_under_one_name() {
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    config.erasure("compliance", (label, ctx) -> true);

    assertThatThrownBy(() -> config.erasure("compliance", (label, ctx) -> false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("two erasures are registered as 'compliance'");
  }

  /** An erasure's policy is a function, so the manifest can say only that it exists, by name. */
  @Test
  @DisplayName("is listed in the manifest by name")
  void is_listed_in_the_manifest() {
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    config.erasure("compliance", (label, ctx) -> true);

    Manifest manifest = config.manifest();

    assertThat(manifest.erasures())
        .singleElement()
        .satisfies(entry -> assertThat(entry.name()).isEqualTo("compliance"));
    assertThat(manifest.toString()).contains("erasures (1)").contains("compliance");
  }

  /** The orThrow form, for a caller that cannot continue without the erasure having happened. */
  @Test
  @DisplayName("throws from orThrow when refused")
  void throws_from_or_throw_when_refused() {
    DefaultCharter config = new DefaultCharter(TENANT, LEVEL);
    Erasure nobody = config.erasure("nobody", (label, ctx) -> false);
    config.bind(Bindings.of(new MemoryStorage()).withoutIdentity());

    Erased erased = nobody.erase(Occluded.of("occ_never-existed"));

    assertThat(erased.removed()).isEmpty();
    assertThatThrownBy(erased::orThrow)
        .isInstanceOf(RefusedException.class)
        .hasMessageContaining("NO_SUCH_VALUE");
  }
}
