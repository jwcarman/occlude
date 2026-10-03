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

import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.storage.Storage;

/**
 * What a charter's portals can do, which is nothing until the charter is bound.
 *
 * <p>Created empty with the charter and handed to every portal as it is minted, so binding does not
 * walk the portals and install anything: it fills this in, once, and every portal is in force from
 * that instant. Everything the operations need comes from the environment -- where values live and
 * who is asking -- and nothing from the declarations, because each portal carries its own.
 *
 * <p>Not a record, as it once was, because it now has a moment at which it changes. That moment is
 * one compare-and-set, and it is the only place anything here crosses to the threads that use a
 * portal.
 */
final class Operations {

  private record Bound(
      Observing observing,
      Occluding occluding,
      Revealing revealing,
      Querying querying,
      Deriving deriving,
      Erasing erasing,
      Inspecting inspecting) {}

  private final AtomicReference<Bound> bound = new AtomicReference<>();

  /**
   * Brings every portal holding this into force, against this storage and this source of identity.
   *
   * <p>Irreversible: there is no unbinding, no rebinding, and no replacing the storage.
   */
  void bind(
      Axes axes,
      Storage storage,
      AccessContextProvider currentAccess,
      ObservationRegistry observations,
      RefusalListener refusals) {
    // Built first, published once. Whoever loses the compare-and-set -- a second bind, or one that
    // raced the first -- is refused the same way, and what it built is never seen by anything.
    Gate gate = new Gate(axes, currentAccess);
    Trail trail = new Trail(storage, refusals);
    Bound operations =
        new Bound(
            new Observing(observations),
            new Occluding(gate, trail, storage),
            new Revealing(gate, trail, storage),
            new Querying(gate, trail, storage),
            new Deriving(gate, trail, storage),
            new Erasing(gate, trail, storage),
            new Inspecting(gate, trail, storage));
    if (!bound.compareAndSet(null, operations)) {
      throw new IllegalStateException("this charter is already bound");
    }
  }

  /** Whether this has been brought into force. */
  boolean bound() {
    return bound.get() != null;
  }

  Observing observing() {
    return current().observing();
  }

  Occluding occluding() {
    return current().occluding();
  }

  Revealing revealing() {
    return current().revealing();
  }

  Querying querying() {
    return current().querying();
  }

  Deriving deriving() {
    return current().deriving();
  }

  Erasing erasing() {
    return current().erasing();
  }

  Inspecting inspecting() {
    return current().inspecting();
  }

  /**
   * The operations, or a refusal saying why a portal cannot act yet.
   *
   * <p>The refusal does not name the portal. Only a miswired application reaches it, and the stack
   * trace already points at the call that came too early.
   */
  private Bound current() {
    Bound current = bound.get();
    if (current == null) {
      throw new IllegalStateException(
          "a portal cannot be exercised before its charter is bound. Authority is constituted"
              + " while a charter is being written and comes into force when it is bound to"
              + " storage; this one was asked to act before that happened.");
    }
    return current;
  }
}
