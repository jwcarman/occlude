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
package org.jwcarman.occlude.observation;

import io.micrometer.observation.Observation;
import java.util.Locale;
import java.util.Objects;
import org.jwcarman.occlude.storage.AuditRecord;

/**
 * One operation, as telemetry sees it: which operation, at which portal, and how it ended.
 *
 * <p>Nothing else, deliberately. No value, no identifier, no label, no identity: those are what the
 * audit trail protects, and a tracing backend is not encrypted, signed or access-controlled the way
 * the trail is. What is here was fixed when the charter was declared, so it describes the system
 * rather than anything in it -- the same line the manifest draws.
 */
public class OccludeObservationContext extends Observation.Context {

  /** How an operation ended. */
  public enum Outcome {
    /** It did what was asked. */
    ALLOWED,
    /** A gate said no, or what was stored was not as signed or would not decrypt. */
    REFUSED,
    /** Something went wrong that is not a decision: a database down, a key service unreachable. */
    FAILED;

    /** The value as it appears on a metric or a span. */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final AuditRecord.Operation operation;
  private final String portal;
  private Outcome outcome = Outcome.ALLOWED;
  private String reason;
  private String errorType;

  public OccludeObservationContext(AuditRecord.Operation operation, String portal) {
    this.operation = Objects.requireNonNull(operation, "an observation is of some operation");
    this.portal = Objects.requireNonNull(portal, "an observation is at some portal");
  }

  /** What was attempted. */
  public AuditRecord.Operation getOperation() {
    return operation;
  }

  /** The portal's declared name. */
  public String getPortal() {
    return portal;
  }

  /** How it ended; {@link Outcome#ALLOWED} until told otherwise. */
  public Outcome getOutcome() {
    return outcome;
  }

  /** The coarse reason code for a refusal, or {@code null}. Names a rule, never a value. */
  public String getReason() {
    return reason;
  }

  /** The simple class name of what was thrown, or {@code null}. Never its message. */
  public String getErrorType() {
    return errorType;
  }

  /** It was refused, for this reason; {@code thrown} is what said so, when anything did. */
  public void refused(String reason, Throwable thrown) {
    this.outcome = Outcome.REFUSED;
    this.reason = reason;
    this.errorType = thrown == null ? null : thrown.getClass().getSimpleName();
  }

  /** It failed with this, which was not a decision. */
  public void failed(Throwable thrown) {
    this.outcome = Outcome.FAILED;
    this.reason = null;
    this.errorType = thrown.getClass().getSimpleName();
  }
}
