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

import java.util.List;

/**
 * Forgetting a value and everything derived from it.
 *
 * <p>The order below is the contract: a value not held is a recorded refusal; the erasure's policy
 * is consulted, and one that throws is a recorded refusal; and the deletions and their lines are
 * written in one transaction.
 */
final class Erasing {

  private final Gate gate;
  private final Trail trail;
  private final Storage storage;

  Erasing(Gate gate, Trail trail, Storage storage) {
    this.gate = gate;
    this.trail = trail;
    this.storage = storage;
  }

  /**
   * Erases through the portal the caller holds, whose policy arrives with it.
   *
   * <p>A refused line names the erasure that refused, because an application may declare several
   * and the record should say whose policy said no. An allowed line names the root instead, which
   * is what ties every descendant's line to the value its erasure was about.
   */
  Erased erase(Occluded<?> root, ErasureSpec erasure) {
    AccessContext asking = gate.asking();
    StoredMetadata entry =
        trail
            .reading(
                AuditRecord.Operation.ERASE,
                root.id(),
                erasure.name(),
                asking,
                () -> storage.metadata(root.id()))
            .orElse(null);
    if (entry == null) {
      // Recorded like every other operation. Asking to destroy something that is not here is an
      // event worth seeing -- a probe looks exactly like this, repeatedly -- and a trail that
      // records only the attempts that found something cannot show it.
      trail.audit(
          AuditRecord.Operation.ERASE,
          root.id(),
          erasure.name(),
          AuditRecord.Outcome.REFUSED,
          Why.of("no such value"),
          null,
          asking);
      return new Erased.Refused(Erased.Reason.NO_SUCH_VALUE, Gate.NOT_HOLDING + root.id());
    }
    // Application code, so it throws, and a gate that could not decide has not said yes. Left to
    // propagate, an erasure nobody was allowed to attempt left no line saying it was attempted.
    boolean permitted;
    try {
      permitted = erasure.mayErase().test(entry.label(), asking);
    } catch (RuntimeException _) {
      permitted = false;
    }
    if (!permitted) {
      trail.audit(
          AuditRecord.Operation.ERASE,
          root.id(),
          erasure.name(),
          AuditRecord.Outcome.REFUSED,
          Why.of("not permitted to erase"),
          entry.label(),
          asking);
      return new Erased.Refused(
          Erased.Reason.NOT_PERMITTED, "'" + erasure.name() + "' may not erase " + root.id());
    }
    // One line per value, not one per call, and written by the storage inside the same
    // transaction as the deletes. Every other operation writes a line naming the value it acted
    // on, and erasure is where that matters most: it is the only operation that makes a value
    // stop existing, so the trail becomes the only thing that can say the value ever did. A
    // single line saying "41 values removed" cannot tell a lawful erasure from a quiet deletion,
    // because nothing afterwards knows which 41.
    //
    // Handing the line to storage rather than writing them here is what makes it repairable. Done
    // afterwards, a crash between the deletes and the lines leaves values destroyed that the
    // trail never says were destroyed, the chain still verifies, and erasing again finds nothing
    // to erase -- a permanent tamper alarm for something nobody did.
    List<String> removed =
        storage.erase(
            root.id(),
            id ->
                trail.entry(
                    AuditRecord.Operation.ERASE,
                    id,
                    root.id(),
                    AuditRecord.Outcome.ALLOWED,
                    Why.of("erased"),
                    // The root's label, on the root's own line: whose data this erasure was about.
                    // A descendant's label is not in hand, and guessing it would be worse.
                    id.equals(root.id()) ? entry.label() : null,
                    asking));
    return new Erased.Removed(removed.size());
  }
}
