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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.manifest.Manifest;

/**
 * Rendering a {@link Manifest} from what a charter has been told, and nothing else.
 *
 * <p>Package-private and entirely pure: it reads a charter's {@link Declarations} and produces a
 * report. It decides nothing and holds nothing, which is why it does not live on the charter --
 * reporting is not one of a charter's powers, it is a reading of one.
 */
final class Manifests {

  private static final String NO_WRITER = "no-writer";
  private static final String NO_READER = "no-reader";
  private static final String NOT_A_PLAIN_VALUE = "not-a-plain-value";

  /** Values that cannot carry anything anywhere: immutable, and holding nothing but themselves. */
  private static final Set<Class<?>> PLAIN =
      Set.of(
          String.class,
          Boolean.class,
          Character.class,
          Byte.class,
          Short.class,
          Integer.class,
          Long.class,
          Float.class,
          Double.class,
          Void.class,
          BigInteger.class,
          BigDecimal.class,
          UUID.class);

  private Manifests() {}

  /** What this configuration permits, rendered for one access. */
  static Manifest of(Declarations configuration, AccessContext as) {
    return new Manifest(
        String.valueOf(Label.nothing()),
        sourceEntries(configuration),
        sinkEntries(configuration, as),
        derivationEntries(configuration),
        queryEntries(configuration),
        erasureEntries(configuration),
        inspectionEntries(configuration, as),
        findings(configuration),
        as);
  }

  /** An erasure's policy is a function of the label and the access, so only its name can show. */
  private static List<Manifest.Entry> erasureEntries(Declarations configuration) {
    return configuration.erasures().stream()
        .map(
            erasure ->
                new Manifest.Entry(
                    erasure.name(),
                    "may erase what its policy allows, and everything derived from it",
                    false,
                    List.of(),
                    null))
        .toList();
  }

  private static List<Manifest.Entry> inspectionEntries(
      Declarations configuration, AccessContext as) {
    return configuration.inspections().stream()
        .map(
            inspection ->
                new Manifest.Entry(
                    inspection.name(),
                    "reads labels and lineage up to " + accepts(inspection::ceilingFor, as),
                    false,
                    List.of(),
                    null))
        .toList();
  }

  private static List<Manifest.Entry> sourceEntries(Declarations configuration) {
    return configuration.sources().entrySet().stream()
        .map(
            source ->
                new Manifest.Entry(
                    source.getKey(),
                    "accepts a " + source.getValue().name(),
                    false,
                    List.of(),
                    source.getValue().name()))
        .toList();
  }

  private static List<Manifest.Entry> sinkEntries(Declarations configuration, AccessContext as) {
    return configuration.sinks().stream()
        .map(
            sink ->
                new Manifest.Entry(
                    sink.name(),
                    "accepts up to " + accepts(sink::ceiling, as),
                    false,
                    List.copyOf(configuration.sinkReads().getOrDefault(sink.name(), Set.of())),
                    null))
        .toList();
  }

  private static List<Manifest.Entry> derivationEntries(Declarations configuration) {
    return configuration.derivations().stream()
        .map(
            derivation ->
                new Manifest.Entry(
                    derivation.name(),
                    "%s -> %s".formatted(reads(derivation), derivation.outputType().name()),
                    derivation.privileged(),
                    derivation.inputTypes().stream().map(OccludedType::name).distinct().toList(),
                    derivation.outputType().name()))
        .toList();
  }

  private static List<Manifest.Entry> queryEntries(Declarations configuration) {
    return configuration.queries().stream()
        .map(
            query ->
                new Manifest.Entry(
                    query.name(),
                    "asks about "
                        + query.inputType().name()
                        + ", given a "
                        + query.against().getName(),
                    false,
                    List.of(query.inputType().name()),
                    null))
        .toList();
  }

  /**
   * What can be proved about the declarations without running anything.
   *
   * <p>A question about types, and therefore answerable: which types anything can produce, which
   * types anything reads, and whether a path exists between them. A door nobody can reach is dead
   * authority; a door that reads a type nothing makes is usually a rename that went half-applied,
   * and it fails as a refusal at request time rather than at startup, which is the worst moment to
   * find out.
   *
   * <p>Deliberately not a question about labels. A source's label and a sink's ceiling are both
   * functions of the access, so "can an unendorsed value reach the vendor model" has no general
   * answer -- only one per caller. That is what rendering a manifest for an access is for.
   */
  private static List<Manifest.Finding> findings(Declarations configuration) {
    List<Manifest.Finding> findings = new ArrayList<>();
    Set<String> produced = producedTypes(configuration);
    Set<String> read = readTypes(configuration);
    declarationPresenceFindings(configuration, findings);
    unreachableSourceFindings(configuration, findings);
    unproducedSinkReadFindings(configuration, produced, findings);
    derivationFindings(configuration, produced, read, findings);
    unproducedQueryFindings(configuration, produced, findings);
    notAPlainValueFindings(configuration, findings);
    return findings;
  }

  /** Every type something in this charter can produce: a source's, or a derivation's output. */
  private static Set<String> producedTypes(Declarations configuration) {
    return Stream.concat(
            configuration.sources().values().stream(),
            configuration.derivations().stream().map(DerivationSpec::outputType))
        .map(OccludedType::name)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** Every type something in this charter reads: a door's, a derivation's, or a question's. */
  private static Set<String> readTypes(Declarations configuration) {
    Stream<String> byDoors = configuration.sinkReads().values().stream().flatMap(Set::stream);
    Stream<String> byDerivations =
        configuration.derivations().stream()
            .flatMap(derivation -> derivation.inputTypes().stream())
            .map(OccludedType::name);
    Stream<String> byQueries =
        configuration.queries().stream().map(query -> query.inputType().name());
    return Stream.of(byDoors, byDerivations, byQueries)
        .flatMap(names -> names)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /** Whether this charter has any door in and any door out at all. */
  private static void declarationPresenceFindings(
      Declarations configuration, List<Manifest.Finding> findings) {
    if (configuration.sources().isEmpty()) {
      findings.add(
          new Manifest.Finding(
              "no-sources", "(charter)", "nothing can be occluded: no source is declared"));
    }
    if (configuration.sinks().isEmpty()) {
      findings.add(
          new Manifest.Finding(
              "no-sinks", "(charter)", "nothing can be revealed anywhere: no sink is declared"));
    }
  }

  /**
   * A value occluded here can reach a door only if some chain of derivations gets its type to one a
   * door reads. Walked rather than assumed: a derivation in the middle is easy to miss.
   */
  private static void unreachableSourceFindings(
      Declarations configuration, List<Manifest.Finding> findings) {
    for (var source : configuration.sources().entrySet()) {
      if (!reaches(source.getValue().name(), configuration)) {
        findings.add(
            new Manifest.Finding(
                NO_READER,
                source.getKey(),
                "writes '%s', which no sink reads and no derivation turns into one"
                    .formatted(source.getValue().name())));
      }
    }
  }

  /** A door that reads a type nothing in this charter can ever produce. */
  private static void unproducedSinkReadFindings(
      Declarations configuration, Set<String> produced, List<Manifest.Finding> findings) {
    for (var door : configuration.sinkReads().entrySet()) {
      for (String type : door.getValue()) {
        if (!produced.contains(type)) {
          findings.add(
              new Manifest.Finding(
                  NO_WRITER,
                  door.getKey(),
                  "reads '%s', which nothing in this charter can produce".formatted(type)));
        }
      }
    }
  }

  /** A derivation that reads what nothing produces, or makes what nothing reads. */
  private static void derivationFindings(
      Declarations configuration,
      Set<String> produced,
      Set<String> read,
      List<Manifest.Finding> findings) {
    for (DerivationSpec<?> derivation : configuration.derivations()) {
      for (OccludedType<?> input : derivation.inputTypes()) {
        if (!produced.contains(input.name())) {
          findings.add(
              new Manifest.Finding(
                  NO_WRITER,
                  derivation.name(),
                  "reads a %s, which nothing in this charter can produce".formatted(input.name())));
        }
      }
      if (!read.contains(derivation.outputType().name())) {
        findings.add(
            new Manifest.Finding(
                NO_READER,
                derivation.name(),
                "makes '%s', which nothing reads".formatted(derivation.outputType().name())));
      }
    }
  }

  /** A question that asks about a type nothing in this charter can ever produce. */
  private static void unproducedQueryFindings(
      Declarations configuration, Set<String> produced, List<Manifest.Finding> findings) {
    for (QuerySpec<?, ?> query : configuration.queries()) {
      if (!produced.contains(query.inputType().name())) {
        findings.add(
            new Manifest.Finding(
                NO_WRITER,
                query.name(),
                "asks about a %s, which nothing in this charter can produce"
                    .formatted(query.inputType().name())));
      }
    }
  }

  /**
   * A question asked with something that could carry the value away.
   *
   * <p>A question is one bit only if nothing else leaves. Its function is handed the plaintext and
   * whatever it is asked with, so asking with a {@code Consumer}, a {@code StringBuilder} or a list
   * is handing the function somewhere to put the value -- and the answer is still recorded as a
   * question. Not refused, because only whoever wrote the function knows what it does; listed,
   * because that is exactly what a review has to look at.
   */
  private static void notAPlainValueFindings(
      Declarations configuration, List<Manifest.Finding> findings) {
    for (QuerySpec<?, ?> query : configuration.queries()) {
      if (!plain(query.against(), new HashSet<>())) {
        findings.add(
            new Manifest.Finding(
                NOT_A_PLAIN_VALUE,
                query.name(),
                ("is asked with a %s, which is not a plain value: its function could hand the"
                        + " value it reads to whatever that can reach")
                    .formatted(query.against().getName())));
      }
    }
  }

  /**
   * Immutable and self-contained: a primitive or its box, a string, an exact number, an id, an
   * enum, a {@code java.time} value, or a record made only of those.
   */
  private static boolean plain(Class<?> type, Set<Class<?>> visiting) {
    if (type.isPrimitive() || type.isEnum() || PLAIN.contains(type)) {
      return true;
    }
    if (type.getPackageName().equals("java.time")) {
      return true;
    }
    if (type.isRecord()) {
      // A record already being looked at is taken as plain here; its other components decide.
      return !visiting.add(type)
          || Arrays.stream(type.getRecordComponents())
              .allMatch(component -> plain(component.getType(), visiting));
    }
    return false;
  }

  /** Whether any sink reads this type, or a type reachable from it by deriving. */
  private static boolean reaches(String type, Declarations configuration) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>(List.of(type));
    Set<String> doorsRead = new LinkedHashSet<>();
    configuration.sinkReads().values().forEach(doorsRead::addAll);
    while (!pending.isEmpty()) {
      String next = pending.removeFirst();
      if (!seen.add(next)) {
        continue;
      }
      if (doorsRead.contains(next)) {
        return true;
      }
      for (DerivationSpec<?> derivation : configuration.derivations()) {
        for (OccludedType<?> input : derivation.inputTypes()) {
          if (input.name().equals(next)) {
            pending.add(derivation.outputType().name());
          }
        }
      }
    }
    return false;
  }

  /**
   * What this door accepts for one access.
   *
   * <p>A ceiling that reads the tenant out of the access cannot be rendered without one, and in a
   * multi-tenant application that is every ceiling there is -- so a manifest rendered for nobody
   * printed "could not be evaluated" against every door, which is the report being useless in
   * exactly the case it exists for. It is rendered for an access because there is no such thing as
   * what a door accepts in general.
   *
   * <p>For the manifest only, and evaluated against an empty access when the caller does not supply
   * one, because a manifest is a statement about the system rather than about one request. A
   * ceiling that reads a tenant will refuse to answer that, and saying so is more honest than
   * printing what it would allow nobody.
   */
  private static String accepts(Function<AccessContext, Ceiling> door, AccessContext as) {
    try {
      Ceiling ceiling = door.apply(as);
      return ceiling == null ? "(said nothing for this access)" : ceiling.toString();
    } catch (RuntimeException e) {
      return "(could not decide for this access" + Gate.threw(e) + ")";
    }
  }

  /** How a derivation's parents read: positionally, or as many of one type. */
  private static String reads(DerivationSpec<?> spec) {
    String types =
        spec.inputTypes().stream().map(OccludedType::name).collect(Collectors.joining(", "));
    return spec.fold() ? "many " + types : types;
  }
}
