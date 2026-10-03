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

import java.time.Instant;
import java.util.Objects;
import org.jwcarman.occlude.storage.AuditRecord;

/**
 * One refusal, told to a {@link RefusalListener} after its line is in the record.
 *
 * <p>The event tells who asked and about what. It never carries the value, the label, the detail or
 * an exception, because each of them can hold what the record protects. The record is the truth; an
 * event is a notice.
 *
 * <p>The value id and the access context are here because the listener is application code in the
 * same process. The application made the access context and already holds the occluded reference.
 * An observation goes to a tracing backend, so it carries neither.
 *
 * @param at when the trail wrote the line
 * @param operation what was refused
 * @param portal the portal that refused it
 * @param valueId the value that the line names
 * @param reason the reason that the line gives, such as {@code ABOVE_CEILING} or {@code FAILED}
 * @param context who asked
 */
public record RefusalEvent(
    Instant at,
    AuditRecord.Operation operation,
    String portal,
    String valueId,
    RefusalReason reason,
    AccessContext context) {

  /**
   * Requires every part.
   *
   * @param at when the trail wrote the line
   * @param operation what was refused
   * @param portal the portal that refused it
   * @param valueId the value that the line names
   * @param reason the reason that the line gives
   * @param context who asked
   */
  public RefusalEvent {
    Objects.requireNonNull(at, "a refusal happened at some time");
    Objects.requireNonNull(operation, "a refusal is of some operation");
    Objects.requireNonNull(portal, "a refusal is at some portal");
    Objects.requireNonNull(valueId, "a refusal names the value it is about");
    Objects.requireNonNull(reason, "a refusal gives a reason");
    Objects.requireNonNull(context, "a refusal says who asked, even nobody in particular");
  }
}
