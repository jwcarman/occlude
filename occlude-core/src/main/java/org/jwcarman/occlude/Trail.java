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
import java.util.Optional;
import java.util.function.Supplier;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.AuditRecord;
import org.jwcarman.occlude.storage.Storage;
import org.jwcarman.occlude.storage.StorageIntegrityException;
import org.jwcarman.occlude.storage.StorageUnreadableException;

/**
 * The record every operation writes, and the one way it gets written.
 *
 * <p>Separate from {@link Gate} because deciding and recording are different jobs, and an operation
 * that does both should be seen doing both.
 */
final class Trail {

  private final Storage storage;
  private final RefusalListener refusals;

  Trail(Storage storage, RefusalListener refusals) {
    this.storage = storage;
    this.refusals = refusals;
  }

  /**
   * Writes the line for an operation that was allowed. There is no way to turn this off, which is
   * the point of it.
   *
   * <p>A control whose log is silently dropping entries still produces the report, so an access
   * that cannot be audited does not happen.
   */
  void allowed(
      AuditRecord.Operation operation,
      String value,
      String target,
      Why why,
      Label label,
      AccessContext context) {
    storage.append(
        entry(operation, value, target, AuditRecord.Outcome.ALLOWED, why, label, context));
  }

  /**
   * Writes the line for a refusal, then tells the listener.
   *
   * <p>The reason is a {@link RefusalReason}, never free text, so every refused line gives a code
   * that an alert can match. The prose belongs in the detail, which is encrypted.
   */
  void refused(
      AuditRecord.Operation operation,
      String value,
      String target,
      RefusalReason reason,
      String detail,
      Label label,
      AccessContext context) {
    storage.append(
        entry(
            operation,
            value,
            target,
            AuditRecord.Outcome.REFUSED,
            Why.of(reason.name(), detail),
            label,
            context));
    told(operation, value, target, reason, context);
  }

  /**
   * Tells the listener of a refusal whose line is already written.
   *
   * <p>Only after the store accepts the line, so an event never describes a line that is not in the
   * record. A listener that throws changes nothing: the outcome is already decided. The event is
   * built inside the same guard, so that nothing here can throw past a line that is written.
   */
  private void told(
      AuditRecord.Operation operation,
      String value,
      String target,
      RefusalReason reason,
      AccessContext context) {
    try {
      refusals.refused(new RefusalEvent(Instant.now(), operation, target, value, reason, context));
    } catch (RuntimeException _) {
      // A listener never decides an outcome, and this outcome is already decided.
    }
  }

  /**
   * Reads from storage, and records it if what came back was not what was signed.
   *
   * <p>A store refuses to hand over a field that fails its check, by throwing. Left alone, that
   * exception would pass the audit by: the operation stops, the caller sees a stack trace, and the
   * one party that must know tampering was found -- the record -- never hears of it. So the attempt
   * is written down as refused, and the exception carries on.
   */
  <T> T reading(
      AuditRecord.Operation operation,
      String value,
      String target,
      AccessContext context,
      Supplier<T> read) {
    try {
      return read.get();
    } catch (StorageIntegrityException e) {
      throw recorded(e, RefusalReason.NOT_AS_SIGNED, operation, value, target, context);
    } catch (StorageUnreadableException e) {
      throw recorded(e, RefusalReason.UNREADABLE, operation, value, target, context);
    }
  }

  /**
   * Writes the refusal a failed read deserves, and hands back what failed.
   *
   * <p>The finding is what matters. A record that could not be written -- the store is down -- is
   * attached to it rather than thrown in its place, so the caller still learns what was found.
   */
  private <E extends RuntimeException> E recorded(
      E failure,
      RefusalReason reason,
      AuditRecord.Operation operation,
      String value,
      String target,
      AccessContext context) {
    try {
      refused(operation, value, target, reason, failure.getMessage(), null, context);
    } catch (RuntimeException unrecorded) {
      failure.addSuppressed(unrecorded);
    }
    return failure;
  }

  /**
   * A line not yet written, for the operations that hand it to storage to commit alongside what
   * they store or remove.
   */
  AuditRecord entry(
      AuditRecord.Operation operation,
      String value,
      String target,
      AuditRecord.Outcome outcome,
      Why why,
      Label label,
      AccessContext context) {
    return new AuditRecord(
        operation,
        value,
        Optional.ofNullable(target),
        outcome,
        Optional.ofNullable(why.code()),
        Optional.ofNullable(why.detail()),
        Optional.ofNullable(label).map(Object::toString),
        context.attributes());
  }
}
