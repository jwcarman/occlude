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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jwcarman.occlude.jdbc.IntegrityReport;
import org.jwcarman.occlude.jdbc.StorageIntegrity;
import org.jwcarman.occlude.observation.OccludeFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Checks the store on a schedule, so tampering is noticed by somebody rather than by whoever next
 * happens to read the altered row.
 *
 * <p>Each run is an observation, {@code occlude.integrity}, tagged {@code occlude.integrity.result}
 * = {@code intact}, {@code unreadable}, {@code altered} or {@code failed}: a timer to alert on,
 * with no new dependency. What was found is logged as counts -- which rows, {@link
 * StorageIntegrity#check()} names for whoever investigates -- along with the trail's head, so
 * anchoring can be as simple as shipping that line somewhere the database's writers cannot reach.
 *
 * <p>A run that cannot finish, during a key service outage say, is {@code failed} and changes
 * nothing else: it does not claim the store was checked and found clean.
 */
public class IntegrityMonitor implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(IntegrityMonitor.class);

  /** The run's result, namespaced as the semantic conventions ask of a library's attributes. */
  static final String RESULT = "occlude.integrity.result";

  private final StorageIntegrity integrity;
  private final Duration interval;
  private final ObservationRegistry observations;
  private ScheduledExecutorService scheduler;

  public IntegrityMonitor(
      StorageIntegrity integrity, Duration interval, ObservationRegistry observations) {
    this.integrity = integrity;
    this.interval = interval;
    this.observations = observations;
  }

  @Override
  public synchronized void start() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("occlude-integrity").daemon().factory());
    long every = interval.toMillis();
    scheduler.scheduleWithFixedDelay(this::check, every, every, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    scheduler.shutdownNow();
    scheduler = null;
  }

  @Override
  public synchronized boolean isRunning() {
    return scheduler != null;
  }

  /** One run. Never throws: a scheduled task that throws is never run again. */
  void check() {
    Observation observation = Observation.createNotStarted("occlude.integrity", observations);
    observation.start();
    try {
      IntegrityReport report = integrity.check();
      observation.lowCardinalityKeyValue(RESULT, resultOf(report));
      logged(report);
    } catch (RuntimeException e) {
      observation.lowCardinalityKeyValue(RESULT, "failed");
      observation.error(new OccludeFailure(e));
      log.warn(
          "occlude could not finish checking its store ({}); nothing is concluded from this run",
          e.getClass().getName());
    } finally {
      observation.stop();
    }
  }

  private static String resultOf(IntegrityReport report) {
    if (!report.intact()) {
      return "altered";
    }
    return report.unreadable() ? "unreadable" : "intact";
  }

  private static void logged(IntegrityReport report) {
    if (!report.intact() && log.isErrorEnabled()) {
      log.error(
          "occlude found its store altered: trail broken at line {}, {} value(s) not as signed, {}"
              + " missing, {} present the trail does not account for, {} altered value(s), {}"
              + " altered line(s). StorageIntegrity.check() names them.",
          report.firstBrokenEntry().map(String::valueOf).orElse("(none)"),
          report.brokenValues().size(),
          report.missingValues().size(),
          report.unaccountedValues().size(),
          report.sweep().alteredValues().size(),
          report.sweep().alteredLines().size());
    }
    if (report.unreadable() && log.isWarnEnabled()) {
      log.warn(
          "occlude could not decrypt {} value(s) and {} line(s) with the keys at hand: reconcile"
              + " them against the keys you destroyed, and treat the rest as altered",
          report.sweep().unreadableValues().size(),
          report.sweep().unreadableLines().size());
    }
    report
        .head()
        .ifPresent(
            head ->
                log.info(
                    "occlude trail head {}: publish it somewhere this database cannot reach",
                    head));
  }
}
