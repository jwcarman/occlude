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

import java.util.Optional;
import java.util.function.Supplier;
import org.jwcarman.occlude.lattice.Label;

/**
 * The record every operation writes, and the one way it gets written.
 *
 * <p>Separate from {@link Gate} because deciding and recording are different jobs, and an operation
 * that does both should be seen doing both.
 */
final class Trail {

  private final Storage storage;

  Trail(Storage storage) {
    this.storage = storage;
  }

  /**
   * Writes the record. There is no way to turn this off, which is the point of it.
   *
   * <p>A control whose log is silently dropping entries still produces the report, so an access
   * that cannot be audited does not happen.
   */
  void audit(
      AuditRecord.Operation operation,
      String value,
      String target,
      AuditRecord.Outcome outcome,
      Why why,
      Label label,
      AccessContext context) {
    storage.append(entry(operation, value, target, outcome, why, label, context));
  }

  /** The reason a line gives when what a store holds turned out not to be what it signed. */
  static final String NOT_AS_SIGNED = "NOT_AS_SIGNED";

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
      audit(
          operation,
          value,
          target,
          AuditRecord.Outcome.REFUSED,
          Why.of(NOT_AS_SIGNED, e.getMessage()),
          null,
          context);
      throw e;
    }
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
