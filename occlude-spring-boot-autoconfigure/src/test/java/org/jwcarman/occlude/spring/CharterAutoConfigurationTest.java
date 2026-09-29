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
package org.jwcarman.occlude.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.AccessContext;
import org.jwcarman.occlude.AccessContextProvider;
import org.jwcarman.occlude.Charter;
import org.jwcarman.occlude.DefaultCharter;
import org.jwcarman.occlude.Derivation;
import org.jwcarman.occlude.Erasure;
import org.jwcarman.occlude.Inspection;
import org.jwcarman.occlude.Occlude;
import org.jwcarman.occlude.Occluded;
import org.jwcarman.occlude.OccludedType;
import org.jwcarman.occlude.Query;
import org.jwcarman.occlude.Reveal;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.MemoryStorage;
import org.jwcarman.occlude.storage.Storage;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

/**
 * Wiring, which is the part that fails quietly.
 *
 * <p>Every other test in this repository drives the library directly. None of them can see a bean
 * that silently never matched, and twice that was the actual defect: a condition on a type Spring
 * did not know the charter by, and a sealer that depended on storage contributed by a later
 * auto-configuration. Both compiled, started, and refused every portal at request time.
 *
 * <p>These run the container without one, which is what makes them worth having.
 */
@DisplayName("The charter auto-configuration")
class CharterAutoConfigurationTest {

  private enum Clearance {
    OPEN,
    CLOSED
  }

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Clearance> CLEARANCE =
      Axis.ladder("clearance", Clearance.OPEN, Clearance.CLOSED);
  private static final OccludedType<String> NOTE = OccludedType.of("note", String.class);
  private static final OccludedType<String> SUMMARY = OccludedType.of("summary", String.class);
  private static final OccludedType<String> PHANTOM = OccludedType.of("phantom", String.class);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CharterAutoConfiguration.class));

  /** What an application contributes: its vocabulary, and somewhere to keep things. */
  @Configuration(proxyBeanMethods = false)
  static class AnApplication {

    @Bean
    Axes axes() {
      return Axes.of(TENANT, CLEARANCE);
    }

    @Bean(name = CharterAutoConfiguration.STORAGE, defaultCandidate = false)
    Storage storage() {
      return new MemoryStorage();
    }

    @Bean
    Occlude<String> notes(Charter charter) {
      return charter.source(
          "notes", NOTE, ctx -> Label.of(TENANT, "acme").with(CLEARANCE, Clearance.OPEN));
    }

    @Bean
    Reveal<String> reporting(Charter charter) {
      return charter
          .sink(
              "reporting",
              Ceiling.of(TENANT, Constraint.any())
                  .with(CLEARANCE, Constraint.atMost(Clearance.OPEN)),
              NOTE)
          .reading(NOTE);
    }
  }

  /**
   * An application with enough vocabulary to exercise every section of the charter endpoint: a
   * derivation, a question, and a door that reads a type nothing here ever produces -- the
   * endpoint's one provable finding.
   */
  @Configuration(proxyBeanMethods = false)
  static class ARichApplication {

    @Bean
    Axes axes() {
      return Axes.of(TENANT, CLEARANCE);
    }

    @Bean(name = CharterAutoConfiguration.STORAGE, defaultCandidate = false)
    Storage storage() {
      return new MemoryStorage();
    }

    @Bean
    Occlude<String> notes(Charter charter) {
      return charter.source(
          "notes", NOTE, ctx -> Label.of(TENANT, "acme").with(CLEARANCE, Clearance.OPEN));
    }

    @Bean
    Reveal<String> reporting(Charter charter) {
      return charter
          .sink(
              "reporting",
              Ceiling.of(TENANT, Constraint.any())
                  .with(CLEARANCE, Constraint.atMost(Clearance.OPEN)),
              NOTE)
          .reading(NOTE);
    }

    /** Reads a type nothing in this charter can ever produce -- a finding by construction. */
    @Bean
    Reveal<String> phantomSink(Charter charter) {
      return charter
          .sink(
              "phantom-sink",
              Ceiling.of(TENANT, Constraint.any())
                  .with(CLEARANCE, Constraint.atMost(Clearance.OPEN)),
              PHANTOM)
          .reading(PHANTOM);
    }

    @Bean
    Derivation<String, String> summarize(Charter charter) {
      return charter.derivation(
          "summarize",
          NOTE,
          SUMMARY,
          note -> note.substring(0, Math.min(3, note.length())),
          d ->
              d.accepting(
                  Ceiling.of(TENANT, Constraint.any())
                      .with(CLEARANCE, Constraint.atMost(Clearance.OPEN))));
    }

    @Bean
    Query<String, Integer> longerThan(Charter charter) {
      return charter.query(
          "longer-than",
          NOTE,
          Integer.class,
          (value, against, ctx) -> value.length() > against,
          q ->
              q.accepting(
                  Ceiling.of(TENANT, Constraint.any())
                      .with(CLEARANCE, Constraint.atMost(Clearance.OPEN))));
    }

    @Bean
    Erasure compliance(Charter charter) {
      return charter.erasure("compliance", (label, ctx) -> ctx.has("role", "compliance"));
    }

    @Bean
    Inspection supportDesk(Charter charter) {
      return charter.inspection(
          "support-desk",
          Ceiling.of(TENANT, Constraint.any()).with(CLEARANCE, Constraint.atMost(Clearance.OPEN)));
    }
  }

  @Test
  @DisplayName("constitutes a charter from the axes an application declared")
  void constitutes_a_charter_from_the_axes() {
    runner
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Charter.class);
              assertThat(context.getBean(Charter.class).axes())
                  .isEqualTo(Axes.of(TENANT, CLEARANCE));
            });
  }

  /**
   * The manifest is meant to be seen -- {@link CharterProperties} says so -- so whether it goes to
   * the log is the property's whole reason to exist, and the only way to prove the property does
   * anything is to watch the logger it controls. The same logger says when identity was left to the
   * default.
   */
  @Nested
  @DisplayName("logging at startup")
  class LoggingAtStartup {

    private Logger binderLog;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
      binderLog = (Logger) LoggerFactory.getLogger(CharterAutoConfiguration.class);
      appender = new ListAppender<>();
      appender.start();
      binderLog.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
      binderLog.detachAppender(appender);
    }

    @Test
    @DisplayName("logs it by default")
    void logs_it_by_default() {
      runner
          .withUserConfiguration(AnApplication.class)
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.INFO);
    }

    @Test
    @DisplayName("stays quiet when an application says not to")
    void stays_quiet_when_told_not_to() {
      runner
          .withUserConfiguration(AnApplication.class)
          .withPropertyValues("occlude.log-manifest=false")
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.INFO);
    }

    @Test
    @DisplayName("warns when nothing says where identity comes from")
    void warns_without_an_access_source() {
      runner
          .withUserConfiguration(AnApplication.class)
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(appender.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("AccessContextProvider"));
    }

    @Test
    @DisplayName("does not warn when the application says where identity comes from")
    void stays_quiet_with_an_access_source() {
      runner
          .withUserConfiguration(AnApplication.class, AnIdentity.class)
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }
  }

  /**
   * The failure that started this file.
   *
   * <p>Binding happens in a {@code SmartInitializingSingleton}, so a portal that works is the only
   * evidence it ran at all. When the sealer that preceded it silently never matched, every portal
   * refused at request time -- and nothing in the wiring complained.
   */
  @Test
  @DisplayName("binds it, so a portal declared against it actually works")
  void binds_it_so_portals_work() {
    runner
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();

              Occlude<String> notes =
                  context
                      .<Occlude<String>>getBeanProvider(
                          ResolvableType.forClassWithGenerics(Occlude.class, String.class))
                      .getObject();
              Reveal<String> reporting =
                  context
                      .<Reveal<String>>getBeanProvider(
                          ResolvableType.forClassWithGenerics(Reveal.class, String.class))
                      .getObject();

              Occluded<String> held = notes.occlude("a note");

              // The value coming back out is the evidence. A portal that refuses at request time
              // is exactly what a charter that was never bound produces, and it is what this
              // test exists to catch.
              assertThat(reporting.reveal(held).granted()).contains("a note");
            });
  }

  /**
   * The endpoint reports declarations, and the drill-down answers about one type.
   *
   * <p>Worth a wiring test rather than a unit test: the endpoint only exists when Actuator is on
   * the classpath AND the endpoint has been exposed, and a condition that silently never matches is
   * how this module has failed before.
   */
  @Test
  @DisplayName("exposes a charter endpoint that can be drilled into by occluded type")
  void exposes_a_charter_endpoint() {
    endpointRunner()
        .withPropertyValues("management.endpoints.web.exposure.include=charter")
        .run(
            context -> {
              assertThat(context).hasSingleBean(CharterEndpoint.class);
              CharterEndpoint endpoint = context.getBean(CharterEndpoint.class);

              Map<String, Object> all = endpoint.charter();
              assertThat(all).containsEntry("axes", List.of("tenant", "clearance"));
              assertThat(entries(all, "sources")).contains("notes");
              assertThat(entries(all, "sinks")).contains("reporting");

              // One section on its own.
              assertThat(entries(endpoint.section("sources"), "sources")).contains("notes");
              assertThat(endpoint.section("no-such-section")).isNull();

              // Everything about the one type, and nothing about anything else.
              Map<String, Object> note = endpoint.named("types", "note");
              assertThat(note).containsEntry("type", "note");
              assertThat(entries(note, "occludedBy")).contains("notes");
              assertThat(entries(note, "revealedAt")).contains("reporting");

              // One named declaration.
              assertThat(endpoint.named("sources", "notes")).containsEntry("name", "notes");
              assertThat(endpoint.named("sources", "no-such-door")).isNull();

              // A type nobody declared is answered, not refused.
              Map<String, Object> nothing = endpoint.named("types", "no-such-type");
              assertThat(entries(nothing, "occludedBy")).isEmpty();
              assertThat(entries(nothing, "revealedAt")).isEmpty();
            });
  }

  /**
   * The rest of what the endpoint can be asked: every section by name, a declaration drilled into
   * within each of them, and the one finding this charter can prove -- a door reading a type
   * nothing here can ever produce.
   */
  @Nested
  @DisplayName("drilling further into the endpoint")
  class DrillingFurtherIntoTheEndpoint {

    private final ApplicationContextRunner richRunner =
        runner
            .withConfiguration(
                AutoConfigurations.of(
                    CharterEndpointAutoConfiguration.class, EndpointAutoConfiguration.class))
            .withUserConfiguration(ARichApplication.class)
            .withPropertyValues("management.endpoints.web.exposure.include=charter");

    @Test
    @DisplayName("answers every section by name")
    void answers_every_section_by_name() {
      richRunner.run(
          context -> {
            CharterEndpoint endpoint = context.getBean(CharterEndpoint.class);

            assertThat(entries(endpoint.section("types"), "types"))
                .contains("note", "summary", "phantom");
            assertThat(entries(endpoint.section("sinks"), "sinks"))
                .contains("reporting", "phantom-sink");
            assertThat(entries(endpoint.section("derivations"), "derivations"))
                .contains("summarize");
            assertThat(entries(endpoint.section("questions"), "questions")).contains("longer-than");
            assertThat(entries(endpoint.section("erasures"), "erasures")).contains("compliance");
            assertThat(entries(endpoint.section("inspections"), "inspections"))
                .contains("support-desk");
            assertThat(findingKinds(endpoint.section("findings"))).contains("no-writer");
          });
    }

    @Test
    @DisplayName("drills into one declaration per section")
    void drills_into_one_declaration_per_section() {
      richRunner.run(
          context -> {
            CharterEndpoint endpoint = context.getBean(CharterEndpoint.class);

            assertThat(endpoint.named("sinks", "reporting")).containsEntry("name", "reporting");
            assertThat(endpoint.named("derivations", "summarize"))
                .containsEntry("name", "summarize");
            assertThat(endpoint.named("questions", "longer-than"))
                .containsEntry("name", "longer-than");
            assertThat(endpoint.named("erasures", "compliance"))
                .containsEntry("name", "compliance");
            assertThat(endpoint.named("inspections", "support-desk"))
                .containsEntry("name", "support-desk");

            // A section this endpoint has never heard of, unlike an unmatched name within one.
            assertThat(endpoint.named("no-such-section", "whatever")).isNull();
          });
    }

    /**
     * {@code madeBy} and {@code readBy} are different questions -- what turns into this type, and
     * what this type turns into -- and a type that does both answers each one narrowly.
     */
    @Test
    @DisplayName("tells apart what a type is made from and what it is made into")
    void tells_apart_made_from_and_made_into() {
      richRunner.run(
          context -> {
            CharterEndpoint endpoint = context.getBean(CharterEndpoint.class);

            Map<String, Object> note = endpoint.named("types", "note");
            assertThat(entries(note, "readBy")).contains("summarize");
            assertThat(entries(note, "madeBy")).isEmpty();

            Map<String, Object> summary = endpoint.named("types", "summary");
            assertThat(entries(summary, "madeBy")).contains("summarize");
            assertThat(entries(summary, "readBy")).isEmpty();
          });
    }

    /** The finding a type-level drill-down can prove: a door reading what nothing produces. */
    @Test
    @DisplayName("carries a type's own finding into its drill-down")
    void carries_a_types_own_finding() {
      richRunner.run(
          context -> {
            CharterEndpoint endpoint = context.getBean(CharterEndpoint.class);

            Map<String, Object> phantom = endpoint.named("types", "phantom");
            assertThat(findingKinds(phantom)).contains("no-writer");
          });
    }
  }

  /** And it is not there for an application that never exposed it. */
  @Test
  @DisplayName("does not expose the endpoint unless it was asked for")
  void does_not_expose_the_endpoint_by_default() {
    endpointRunner().run(context -> assertThat(context).doesNotHaveBean(CharterEndpoint.class));
  }

  /** The endpoint infrastructure too, because that is what decides whether one is available. */
  private ApplicationContextRunner endpointRunner() {
    return runner
        .withConfiguration(
            AutoConfigurations.of(
                CharterEndpointAutoConfiguration.class, EndpointAutoConfiguration.class))
        .withUserConfiguration(AnApplication.class);
  }

  @SuppressWarnings("unchecked")
  private static List<String> entries(Map<String, Object> report, String section) {
    return ((List<Map<String, Object>>) report.get(section))
        .stream().map(entry -> (String) entry.get("name")).toList();
  }

  /** {@code findings} entries are shaped differently from every other section: no {@code name}. */
  private static List<String> findingKinds(Map<String, Object> report) {
    List<?> findings = (List<?>) report.get("findings");
    return findings.stream()
        .map(entry -> (Map<?, ?>) entry)
        .map(entry -> (String) entry.get("kind"))
        .toList();
  }

  /** An application with no vocabulary gets no charter, rather than a guessed one. */
  @Test
  @DisplayName("declares nothing when the application never said what it asks about values")
  void declares_nothing_without_axes() {
    runner.run(context -> assertThat(context).doesNotHaveBean(Charter.class));
  }

  /**
   * Storage arrives from whichever module is on the classpath, and that runs afterwards.
   *
   * <p>A direct dependency here was evaluated before the bean existed, so the sealer never matched.
   * This is the regression test for that, and it passes only because resolution is deferred to the
   * moment of binding.
   */
  @Test
  @DisplayName("binds against storage contributed by a later auto-configuration")
  void binds_against_storage_contributed_later() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CharterAutoConfiguration.class, LateStorage.class))
        .withUserConfiguration(NoStorage.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(Storage.class);
              assertBound(context.getBean(Charter.class));
            });
  }

  /**
   * Storage the way a storage module really supplies it: from an auto-configuration ordered after
   * the one that constitutes the charter, which is precisely when a direct dependency is evaluated
   * too early and the binder silently never matches.
   */
  @AutoConfiguration(after = CharterAutoConfiguration.class)
  static class LateStorage {

    @Bean(name = CharterAutoConfiguration.STORAGE, defaultCandidate = false)
    Storage storage() {
      return new MemoryStorage();
    }
  }

  /**
   * A charter with nowhere to keep anything fails at startup, never as refusals at request time.
   */
  @Test
  @DisplayName("fails to start when an application declares a charter and supplies no storage")
  void fails_to_start_without_storage() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CharterAutoConfiguration.class))
        .withUserConfiguration(NoStorage.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasStackTraceContaining(Storage.class.getName()));
  }

  @Configuration(proxyBeanMethods = false)
  static class NoStorage {

    @Bean
    Axes axes() {
      return Axes.of(TENANT);
    }
  }

  /**
   * The published bean cannot bring a charter into force, and cannot destroy through one.
   *
   * <p>Not a check that refuses: {@link Charter} has no {@code bind} and no {@code erase} to call.
   * Erasing takes an {@link org.jwcarman.occlude.Erasure}, which somebody has to have declared and
   * handed over. What the container hands out is narrower than what the starter kept.
   */
  @Test
  @DisplayName("publishes a charter that can neither bind nor erase")
  void publishes_a_charter_that_cannot_bind() {
    assertThat(Charter.class.getMethods())
        .isNotEmpty()
        .noneSatisfy(method -> assertThat(method.getName()).isEqualTo("bind"))
        .noneSatisfy(method -> assertThat(method.getName()).isEqualTo("erase"));
  }

  /** Identity is the wiring's, so an application that says where it lives is taken at its word. */
  @Test
  @DisplayName("binds with the access source the application contributed")
  void binds_with_the_applications_access_source() {
    runner
        .withUserConfiguration(AnApplication.class, AnIdentity.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              AnIdentity identity = context.getBean(AnIdentity.class);
              Occlude<String> notes =
                  context
                      .<Occlude<String>>getBeanProvider(
                          ResolvableType.forClassWithGenerics(Occlude.class, String.class))
                      .getObject();

              notes.occlude("a note");

              assertThat(identity.asked).isTrue();
            });
  }

  /** Telemetry is the application's: its registry, when it has one, sees every operation. */
  @Test
  @DisplayName("observes every operation through the application's registry")
  void observes_through_the_applications_registry() {
    runner
        .withUserConfiguration(AnApplication.class, Observed.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Occlude<String> notes =
                  context
                      .<Occlude<String>>getBeanProvider(
                          ResolvableType.forClassWithGenerics(Occlude.class, String.class))
                      .getObject();

              notes.occlude("a note");

              assertThat(context.getBean(Observed.class).names)
                  .containsExactly("occlude.operation");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class Observed {

    private final List<String> names = new ArrayList<>();

    @Bean
    ObservationRegistry observationRegistry() {
      ObservationRegistry registry = ObservationRegistry.create();
      registry
          .observationConfig()
          .observationHandler(
              new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                  names.add(context.getName());
                }
              });
      return registry;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnIdentity {

    private boolean asked;

    @Bean
    AccessContextProvider currentAccess() {
      return () -> {
        asked = true;
        return AccessContext.of("tenant", "acme");
      };
    }
  }

  /** A bound charter says so the only way it can: by refusing to grow. */
  private static void assertBound(Charter charter) {
    assertThatThrownBy(() -> charter.erasure("probe", (label, ctx) -> true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has been bound");
  }

  /** And an application that brought its own charter keeps it. */
  @Test
  @DisplayName("leaves a charter the application declared itself alone")
  void leaves_an_application_charter_alone() {
    runner
        .withUserConfiguration(OwnCharter.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Charter.class);
              assertThat(context.getBean(Charter.class).axes()).isEqualTo(Axes.of(TENANT));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnCharter {

    @Bean
    Axes axes() {
      return Axes.of(TENANT, CLEARANCE);
    }

    @Bean
    Charter charter() {
      return new DefaultCharter(TENANT);
    }

    @Bean(name = CharterAutoConfiguration.STORAGE, defaultCandidate = false)
    Storage storage() {
      return new MemoryStorage();
    }
  }

  /** Declaring after the context has finished is the forged-portal case, through the container. */
  @Test
  @DisplayName("refuses anything declared after the context has been built")
  void refuses_anything_declared_afterwards() {
    runner
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              Charter charter = context.getBean(Charter.class);

              assertThatThrownBy(
                      () ->
                          charter.source(
                              "forged",
                              NOTE,
                              ctx -> Label.of(TENANT, "globex").with(CLEARANCE, Clearance.OPEN)))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("has been bound");
            });
  }
}
