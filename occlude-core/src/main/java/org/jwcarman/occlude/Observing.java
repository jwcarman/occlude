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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jwcarman.occlude.observation.DefaultOccludeObservationConvention;
import org.jwcarman.occlude.observation.OccludeObservationContext;
import org.jwcarman.occlude.observation.OccludeObservationDocumentation;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.StorageIntegrityException;
import org.jwcarman.occlude.storage.StorageUnreadableException;

/**
 * Every operation as one Micrometer observation, around all of it.
 *
 * <p>Around the whole operation -- gate, storage, decryption, derivation, the audit line -- so it
 * is a timer and a span at once, and a trace shows the database calls inside the operation that
 * made them.
 *
 * <p>Never {@code observation.error(e)}. It attaches the exception, and handlers and tracing
 * bridges put its message on the span, where a storage exception's message names a value. What was
 * thrown is recorded by class name on the context instead.
 *
 * <p>Telemetry never decides an outcome: a handler that throws is ignored, and the operation ends
 * exactly as it would have with nobody watching.
 */
final class Observing {

  private static final DefaultOccludeObservationConvention CONVENTION =
      new DefaultOccludeObservationConvention();

  private final ObservationRegistry registry;

  Observing(ObservationRegistry registry) {
    this.registry = registry;
  }

  <R> R observe(
      AuditRecord.Operation operation,
      String portal,
      Supplier<R> work,
      BiConsumer<OccludeObservationContext, R> settle) {
    OccludeObservationContext context = new OccludeObservationContext(operation, portal);
    Observation observation =
        OccludeObservationDocumentation.OPERATION.observation(
            null, CONVENTION, () -> context, registry);
    quietly(observation::start);
    Observation.Scope scope = scoped(observation);
    try {
      R result = work.get();
      settle.accept(context, result);
      return result;
    } catch (StorageIntegrityException e) {
      context.refused(Trail.NOT_AS_SIGNED, e);
      throw e;
    } catch (StorageUnreadableException e) {
      context.refused(Trail.UNREADABLE, e);
      throw e;
    } catch (RefusedException e) {
      context.refused(e.reason(), null);
      throw e;
    } catch (RuntimeException e) {
      context.failed(e);
      throw e;
    } finally {
      quietly(scope::close);
      quietly(observation::stop);
    }
  }

  /** Nothing to add: an operation that returned is allowed unless its result says otherwise. */
  static <R> void returned(OccludeObservationContext context, R result) {
    // The context starts out allowed.
  }

  static void revealed(OccludeObservationContext context, Revealed<?> result) {
    if (result instanceof Revealed.Denied<?> denied) {
      context.refused(denied.reason().name(), null);
    }
  }

  static void derived(OccludeObservationContext context, Derived<?> result) {
    if (result instanceof Derived.Refused<?> refused) {
      context.refused(refused.reason().name(), null);
    }
  }

  static void answered(OccludeObservationContext context, Answer result) {
    if (result instanceof Answer.Refused refused) {
      context.refused(refused.reason().name(), null);
    }
  }

  static void erased(OccludeObservationContext context, Erased result) {
    if (result instanceof Erased.Refused refused) {
      context.refused(refused.reason().name(), null);
    }
  }

  static void inspected(OccludeObservationContext context, Inspected result) {
    if (result instanceof Inspected.Refused refused) {
      context.refused(refused.reason().name(), null);
    }
  }

  private static Observation.Scope scoped(Observation observation) {
    try {
      return observation.openScope();
    } catch (RuntimeException _) {
      return Observation.Scope.NOOP;
    }
  }

  private static void quietly(Runnable telemetry) {
    try {
      telemetry.run();
    } catch (RuntimeException _) {
      // Telemetry is somebody else's code, and it does not get a vote on the outcome.
    }
  }
}
