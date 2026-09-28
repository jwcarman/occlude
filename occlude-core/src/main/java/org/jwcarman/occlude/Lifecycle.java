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

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jwcarman.occlude.lattice.Axes;

/**
 * Where a charter is in its one irreversible transition.
 *
 * <p>Every portal a charter constitutes shares this one reference, so sealing does not walk them
 * and install anything -- it changes the state of the domain they all belong to, and they are all
 * in force from that instant.
 *
 * <p>Its own type, rather than nested in {@link DefaultCharter}, for the same reason {@link
 * Configuration} is: the portals reach the operations through this, and the charter mints the
 * portals. Nested, each would need the other to compile.
 */
final class Lifecycle {

  /** What sealing produced: the declarations as they were frozen, and what they can now do. */
  private record Sealed(Configuration configuration, Operations operations) {}

  /**
   * Empty while the charter is being written, and set exactly once when it is sealed. There is no
   * third state and no way back.
   */
  private final AtomicReference<Sealed> sealed = new AtomicReference<>();

  /**
   * Brings everything into force against this storage, once.
   *
   * <p>The configuration is taken only after the state is known to allow sealing, and the
   * operations are built before the state changes, so a configuration they refuse -- two sinks or
   * two derivations under one name -- leaves the charter still being written.
   */
  void seal(Axes axes, Supplier<Configuration> declared, Storage storage) {
    if (sealed.get() != null) {
      throw new IllegalStateException("this charter is already sealed");
    }
    Configuration configuration = declared.get();
    Gate gate = new Gate(axes, configuration.currentAccess(), storage);
    Trail trail = new Trail(storage);
    Operations operations =
        new Operations(
            gate,
            new Occluding(gate, trail, storage),
            new Revealing(gate, trail, storage, configuration.sinks()),
            new Querying(gate, trail, storage),
            new Deriving(gate, trail, storage, configuration.derivations()),
            new Erasing(gate, trail, storage, configuration.mayErase()));
    // One write, and every portal this charter constituted is in force. It is also the only moment
    // any of this crosses a thread, which is why the snapshot above is taken first.
    if (!sealed.compareAndSet(null, new Sealed(configuration, operations))) {
      throw new IllegalStateException("this charter was sealed while it was being sealed");
    }
  }

  /** Whether this charter has been brought into force. */
  boolean sealed() {
    return sealed.get() != null;
  }

  /**
   * Refuses anything further once this charter is in force.
   *
   * <p>A security invariant rather than an ergonomic one: an authority graph that can still grow is
   * not one anybody can reason about. Writing a charter is single-threaded by contract, so this is
   * a state check rather than a transition -- there is nothing to race with, because nothing else
   * is declaring and the only thread that could seal is this one.
   */
  void stillWriting() {
    if (sealed.get() != null) {
      throw new IllegalStateException(
          "nothing further can be declared: this charter has been sealed, and an authority graph"
              + " that can still grow is not one anybody can reason about");
    }
  }

  /**
   * The operations a portal reaches through, or a refusal saying why it cannot.
   *
   * <p>The refusal does not name the portal. Only a miswired application reaches it, and the stack
   * trace already points at the call that came too early.
   */
  Operations operations() {
    Sealed current = sealed.get();
    if (current == null) {
      throw new IllegalStateException(
          "a portal cannot be exercised before its charter is sealed. Authority is constituted"
              + " while a charter is being written and comes into force when it is sealed;"
              + " this one was asked to act before that happened.");
    }
    return current.operations();
  }

  /**
   * What has been declared, from wherever it currently lives.
   *
   * <p>While writing, that is whatever the charter's own fields say, read on the thread that is
   * writing them. Once sealed it is the snapshot, read through the atomic that published it --
   * which is what makes reporting safe from a request thread.
   */
  Configuration configuration(Supplier<Configuration> whileWriting) {
    Sealed current = sealed.get();
    return current == null ? whileWriting.get() : current.configuration();
  }
}
