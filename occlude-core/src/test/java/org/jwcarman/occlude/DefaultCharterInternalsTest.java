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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.manifest.Manifest;
import org.jwcarman.occlude.storage.MemoryStorage;

/**
 * What a charter refuses while it is being written, what it hands back about itself, and what its
 * portals say when printed -- none of it a decision, all of it worth being right.
 */
@DisplayName("A charter's own bookkeeping")
class DefaultCharterInternalsTest {

  private static final OccludedType<String> STRING_TYPE = OccludedType.of(String.class);
  private static final OccludedType<Integer> INT_TYPE = OccludedType.of(Integer.class);
  private static final Axis<String> TENANT = Axis.matching("tenant");

  @Nested
  @DisplayName("binding")
  class Binding {

    @Test
    @DisplayName("refuses to be bound twice")
    void refuses_to_be_bound_twice() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.bind(new MemoryStorage(), AccessContextProvider.none());
      MemoryStorage secondStorage = new MemoryStorage();
      AccessContextProvider nobody = AccessContextProvider.none();

      assertThatThrownBy(() -> charter.bind(secondStorage, nobody))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already bound");
    }

    @Test
    @DisplayName("refuses a storage or an access source that is not there")
    void refuses_nulls() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      MemoryStorage storage = new MemoryStorage();
      AccessContextProvider nobody = AccessContextProvider.none();

      assertThatThrownBy(() -> charter.bind(null, nobody)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> charter.bind(storage, null))
          .isInstanceOf(NullPointerException.class);
    }

    /**
     * The one true race: two threads both observe an unbound charter before either publishes, both
     * build the operations, and only one write can win the compare-and-set. The loser must say so
     * rather than silently discard what it built.
     */
    @Test
    @DisplayName("refuses when two threads race to bind it at once")
    void refuses_when_two_threads_race_to_bind_it() throws InterruptedException {
      DefaultCharter charter = new DefaultCharter(TENANT);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch go = new CountDownLatch(1);
      AtomicInteger failures = new AtomicInteger();
      AtomicInteger successes = new AtomicInteger();
      Runnable bindAttempt =
          () -> {
            ready.countDown();
            awaitUninterruptibly(go);
            try {
              charter.bind(new MemoryStorage(), AccessContextProvider.none());
              successes.incrementAndGet();
            } catch (IllegalStateException _) {
              failures.incrementAndGet();
            }
          };
      Thread first = new Thread(bindAttempt);
      Thread second = new Thread(bindAttempt);
      first.start();
      second.start();
      ready.await();
      go.countDown();
      first.join();
      second.join();

      assertThat(successes.get()).isEqualTo(1);
      assertThat(failures.get()).isEqualTo(1);
      assertThatThrownBy(() -> charter.erasure("late", (label, ctx) -> true))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("has been bound");
    }

    /**
     * A declaration racing a bind either lands before it -- and the manifest lists it -- or is
     * refused. What must never happen is the third outcome: a portal minted after binding that no
     * manifest knows about.
     */
    @Test
    @DisplayName("lists every declaration that won a race with binding, and refuses the rest")
    void lists_every_declaration_that_won_the_race() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      List<String> declared = Collections.synchronizedList(new ArrayList<>());
      List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch go = new CountDownLatch(1);
      try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
        for (int writer = 0; writer < 4; writer++) {
          int w = writer;
          pool.submit(
              () -> {
                awaitUninterruptibly(go);
                for (int i = 0; i < 200; i++) {
                  String name = "erasure-" + w + "-" + i;
                  try {
                    charter.erasure(name, (label, ctx) -> true);
                    declared.add(name);
                  } catch (IllegalStateException refused) {
                    if (!refused.getMessage().contains("has been bound")) {
                      unexpected.add(refused);
                    }
                  }
                }
              });
        }
        go.countDown();
        charter.bind(new MemoryStorage(), AccessContextProvider.none());
      }

      assertThat(unexpected).isEmpty();
      assertThat(charter.manifest().erasures())
          .extracting(Manifest.Entry::name)
          .containsExactlyInAnyOrderElementsOf(declared);
    }

    /**
     * Rendering a manifest runs the application's ceilings. Holding the charter's lock while they
     * ran let a slow one hold up binding for as long as it liked.
     */
    @Test
    @DisplayName("binds while a manifest is still waiting on a slow ceiling")
    void binds_while_a_manifest_waits_on_a_ceiling() throws InterruptedException {
      DefaultCharter charter = new DefaultCharter(TENANT);
      CountDownLatch inCeiling = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      charter.sink(
          "slow",
          context -> {
            inCeiling.countDown();
            awaitUninterruptibly(release);
            return Ceiling.of(TENANT, Constraint.any());
          },
          STRING_TYPE);
      Thread rendering = Thread.ofVirtual().start(charter::manifest);
      inCeiling.await();

      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () -> charter.bind(new MemoryStorage(), AccessContextProvider.none()));

      release.countDown();
      rendering.join();
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
      boolean interrupted = false;
      try {
        while (true) {
          try {
            latch.await();
            return;
          } catch (InterruptedException _) {
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Nested
  @DisplayName("declaring")
  class Declaring {

    @Test
    @DisplayName("refuses two sources registered as the same name")
    void refuses_two_sources_with_the_same_name() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
      Label duplicateLabel = Label.of(TENANT, "acme");

      assertThatThrownBy(() -> charter.source("mail", STRING_TYPE, duplicateLabel))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("mail");
    }

    @Test
    @DisplayName("refuses a derivation that never said what it may read")
    void refuses_a_derivation_that_never_said_what_it_may_read() {
      DefaultCharter charter = new DefaultCharter(TENANT);

      assertThatThrownBy(
              () ->
                  charter.derivation(
                      "upper", STRING_TYPE, STRING_TYPE, String::toUpperCase, d -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("needs a ceiling");
    }

    @Test
    @DisplayName("refuses a question that never said what it may read")
    void refuses_a_question_that_never_said_what_it_may_read() {
      DefaultCharter charter = new DefaultCharter(TENANT);

      assertThatThrownBy(
              () ->
                  charter.query(
                      "mentions", STRING_TYPE, String.class, (v, q, ctx) -> true, d -> {}))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("needs a ceiling");
    }
  }

  @Nested
  @DisplayName("a portal, printed")
  class APortalPrinted {

    private final DefaultCharter charter = new DefaultCharter(TENANT);

    private final Occlude<String> source =
        charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));

    private final Sink outboxSink =
        charter.sink("outbox", Ceiling.of(TENANT, Constraint.any()), STRING_TYPE);

    private final Reveal<String> outbox = outboxSink.reading(STRING_TYPE);

    private final Derivation<String, String> upper =
        charter.derivation(
            "upper",
            STRING_TYPE,
            STRING_TYPE,
            String::toUpperCase,
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

    private final Fold<String, String> joined =
        charter.fold(
            "joined",
            STRING_TYPE,
            STRING_TYPE,
            values -> String.join(",", values),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

    private final Erasure compliance = charter.erasure("compliance", (label, ctx) -> true);

    private final Inspection desk =
        charter.inspection("desk", Ceiling.of(TENANT, Constraint.any()));

    {
      charter.bind(new MemoryStorage(), AccessContextProvider.none());
    }

    @Test
    @DisplayName("a source names itself")
    void a_source_names_itself() {
      assertThat(source.toString()).contains("mail");
    }

    @Test
    @DisplayName("a reader says what it reads and names its door")
    void a_reader_says_what_it_reads_and_names_its_door() {
      assertThat(outbox.type()).isEqualTo(STRING_TYPE);
      assertThat(outbox.toString()).contains("outbox").contains("string");
    }

    @Test
    @DisplayName("every other portal names itself too")
    void every_other_portal_names_itself() {
      assertThat(outboxSink).hasToString("sink 'outbox'");
      assertThat(upper).hasToString("derivation 'upper'");
      assertThat(joined).hasToString("fold 'joined'");
      assertThat(compliance).hasToString("erasure 'compliance'");
      assertThat(desk).hasToString("inspection 'desk'");
    }
  }

  @Nested
  @DisplayName("what it reports about itself")
  class WhatItReportsAboutItself {

    private final MemoryStorage storage = new MemoryStorage();

    private final DefaultCharter charter = new DefaultCharter(TENANT);

    private final Occlude<String> source =
        charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));

    private final Query<String, String> mentions =
        charter.query(
            "mentions",
            STRING_TYPE,
            String.class,
            (v, q, ctx) -> v.contains(q),
            d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

    {
      charter.derivation(
          "upper",
          STRING_TYPE,
          STRING_TYPE,
          String::toUpperCase,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
      charter.bind(storage, AccessContextProvider.none());
    }

    @Test
    @DisplayName("the axes it was constituted with")
    void the_axes_it_was_constituted_with() {
      assertThat(charter.axes().named("tenant")).isPresent();
    }

    @Test
    @DisplayName("the sources it was declared with, and the type each accepts")
    void the_sources_it_was_declared_with() {
      assertThat(charter.manifest().sources())
          .singleElement()
          .satisfies(
              entry -> {
                assertThat(entry.name()).isEqualTo("mail");
                assertThat(entry.writes()).isEqualTo("string");
              });
    }

    @Test
    @DisplayName("the derivations it was declared with")
    void the_derivations_it_was_declared_with() {
      assertThat(charter.manifest().derivations())
          .extracting(Manifest.Entry::name)
          .containsExactly("upper");
    }

    @Test
    @DisplayName("the questions it was declared with")
    void the_questions_it_was_declared_with() {
      assertThat(charter.manifest().questions())
          .extracting(Manifest.Entry::name)
          .containsExactly("mentions");
    }

    @Test
    @DisplayName("a question names itself")
    void a_question_names_itself() {
      assertThat(mentions.toString()).contains("mentions");
    }

    /**
     * An application that declares no erasure keeps a store nothing can erase from -- not a policy
     * that says no, but no portal to ask -- and the manifest says so.
     */
    @Test
    @DisplayName("says nothing can be erased when no erasure was declared")
    void says_nothing_can_be_erased_without_an_erasure() {
      Occluded<String> held = source.occlude("hello");

      assertThat(charter.manifest().erasures()).isEmpty();
      assertThat(charter.manifest().toString()).contains("nothing can be erased");
      assertThat(storage.contains(held.id())).isTrue();
    }
  }

  @Nested
  @DisplayName("the manifest's findings")
  class ManifestFindings {

    /**
     * Distinct from a ceiling that threw, the way an application might answer if it modelled "not
     * applicable" as null rather than as a refusal. A sink's ceiling refuses to hand back null, so
     * an inspection's is where this can happen.
     */
    @Test
    @DisplayName("a ceiling that answers with nothing says so, not that it could not decide")
    void a_ceiling_that_answers_with_nothing_says_so() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.inspection("desk", ctx -> null);

      assertThat(charter.manifest().toString()).contains("said nothing for this access");
    }

    @Test
    @DisplayName("a ceiling that throws says it could not decide")
    void a_ceiling_that_throws_says_it_could_not_decide() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.sink(
          "outbox",
          ctx -> {
            throw new IllegalStateException("no tenant");
          },
          STRING_TYPE);

      assertThat(charter.manifest().toString()).contains("could not decide for this access");
    }

    @Test
    @DisplayName("nothing may be revealed anywhere when no sink is declared")
    void nothing_may_be_revealed_with_no_sink_declared() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));

      assertThat(charter.manifest().findings("no-sinks")).isNotEmpty();
    }

    /**
     * The input type has to differ from the output: a derivation always produces its own output
     * type, so reusing it as the input would make the derivation its own writer.
     */
    @Test
    @DisplayName("a derivation that reads a type nothing produces")
    void a_derivation_that_reads_a_type_nothing_produces() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.derivation(
          "length",
          STRING_TYPE,
          INT_TYPE,
          String::length,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

      assertThat(charter.manifest().findings("no-writer"))
          .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("length"));
    }

    @Test
    @DisplayName("a derivation that makes a type nothing reads")
    void a_derivation_that_makes_a_type_nothing_reads() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
      charter.derivation(
          "upper",
          STRING_TYPE,
          INT_TYPE,
          s -> s.length(),
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

      assertThat(charter.manifest().findings("no-reader"))
          .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("upper"));
    }

    @Test
    @DisplayName("a question that asks about a type nothing produces")
    void a_question_that_asks_about_a_type_nothing_produces() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      charter.query(
          "mentions",
          STRING_TYPE,
          String.class,
          (v, q, ctx) -> true,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

      assertThat(charter.manifest().findings("no-writer"))
          .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("mentions"));
    }

    /**
     * A derivation cycle that reaches no sink: the reachability walk must not loop forever
     * revisiting the types it already ruled out.
     */
    @Test
    @DisplayName("does not loop forever walking a cycle of derivations that reaches nowhere")
    void does_not_loop_forever_walking_a_cycle() {
      DefaultCharter charter = new DefaultCharter(TENANT);
      OccludedType<Wrapped> wrappedType = OccludedType.of(Wrapped.class);
      charter.source("mail", STRING_TYPE, Label.of(TENANT, "acme"));
      charter.derivation(
          "wrap",
          STRING_TYPE,
          wrappedType,
          Wrapped::new,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));
      charter.derivation(
          "unwrap",
          wrappedType,
          STRING_TYPE,
          Wrapped::value,
          d -> d.accepting(Ceiling.of(TENANT, Constraint.any())));

      assertThat(charter.manifest().findings("no-reader"))
          .anySatisfy(finding -> assertThat(finding.about()).isEqualTo("mail"));
    }

    record Wrapped(String value) {}
  }
}
