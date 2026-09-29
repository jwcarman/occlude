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
package org.jwcarman.occlude.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of the record: something was asked of a held value, and this is what happened.
 *
 * <p><b>Never the plaintext.</b> An audit says which value, who asked, where it was going and what
 * was decided. If it said what the value was, the audit log would become the largest collection of
 * protected data in the system and the least protected.
 *
 * <p>It does carry the label, because an audit that cannot say <i>why</i> something was refused is
 * not much of an audit. A label can itself be sensitive -- a tenant's name, a project codeword --
 * so an audit trail deserves the same protection as the values it describes.
 *
 * <p>Refusals are recorded as carefully as permissions. A thousand refused attempts against one
 * value is the interesting event, and a log that only records successes cannot show it.
 *
 * @param target what was on the other side: a sink, a derivation, a check
 * @param reason the coarse code, in the clear: it names a rule, not a value, so it stays queryable
 *     -- "how many refusals above a ceiling this hour" is a question a trail should answer without
 *     decrypting anything
 * @param detail what the reason leaves out: which label, against which ceiling. Label-shaped, so it
 *     is protected exactly like {@code label} is. Splitting it from {@code reason} is what keeps
 *     the trail both queryable and closed -- putting the explanation in {@code reason} would have
 *     described every value in the system to anyone who could read the table
 * @param context whatever the application contributed about who was asking
 */
public record AuditRecord(
    Operation operation,
    String value,
    Optional<String> target,
    Outcome outcome,
    Optional<String> reason,
    Optional<String> detail,
    Optional<String> label,
    Map<String, String> context) {

  /** What was being attempted. */
  public enum Operation {
    /** A value was occluded, with labels its caller asserted. */
    CONCEAL,
    /** A value was revealed: plaintext was asked for, on its way somewhere. */
    REVEAL,
    /** A new value was made from one already occluded. */
    DERIVE,
    /** A question was answered about a value without the value leaving. */
    QUERY,
    /** A value and everything derived from it were removed. */
    ERASE,
    /** A value's label and lineage were read, without the value. */
    INSPECT
  }

  public enum Outcome {
    ALLOWED,
    REFUSED
  }

  public AuditRecord {
    Objects.requireNonNull(operation, "an audit record needs an operation");
    // In the order given, as AccessContext keeps it: Map.copyOf would reorder who asked by run.
    Map<String, String> copy = new LinkedHashMap<>();
    context.forEach(
        (name, said) ->
            copy.put(
                Objects.requireNonNull(name, "a context attribute needs a name"),
                Objects.requireNonNull(said, "context attribute '" + name + "' needs a value")));
    context = Collections.unmodifiableMap(copy);
  }

  /**
   * One readable line: what the trail keeps in the clear, and nothing it encrypts.
   *
   * <p>The label, the detail and who was asking are left out. They are encrypted at rest because
   * they are sensitive -- a label names a tenant, the context whoever asked -- and a record handed
   * out by reading the trail back, printed into an ordinary log, would be a second copy of them
   * with none of the trail's protection. It once printed both, for an auditor that simply logged
   * each record; the trail is that record now, and reading it back is how an auditor sees who did
   * what. Anyone who means to log them can still ask for them by name.
   */
  @Override
  public String toString() {
    return "%s %s%s %s%s"
        .formatted(
            operation,
            value,
            target.map(" -> "::concat).orElse(""),
            outcome,
            reason.map(": "::concat).orElse(""));
  }
}
