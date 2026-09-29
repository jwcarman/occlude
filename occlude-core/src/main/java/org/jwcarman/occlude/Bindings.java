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
import java.util.Objects;
import org.jwcarman.occlude.storage.Storage;

/**
 * What a charter is bound to: where values are kept, and where identity comes from.
 *
 * <p>One object rather than a growing parameter list, because binding is where a charter's runtime
 * comes into being and more than storage and identity will arrive there. Built in two steps so that
 * identity is always decided: {@link #of} names the store, and the step it returns offers only
 * {@link Store#withIdentity} or {@link Store#withoutIdentity}.
 *
 * <pre>{@code
 * charter.bind(Bindings.of(storage).withIdentity(() -> AccessContext.of(Map.of(
 *     "tenant", CurrentTenant.get(),
 *     "principal", SecurityContextHolder.getContext().getAuthentication().getName()))));
 * }</pre>
 */
public final class Bindings {

  private final Storage storage;
  private final AccessContextProvider currentAccess;
  private final ObservationRegistry observations;

  private Bindings(
      Storage storage, AccessContextProvider currentAccess, ObservationRegistry observations) {
    this.storage = storage;
    this.currentAccess = currentAccess;
    this.observations = observations;
  }

  /**
   * Binding to this store; identity is decided next.
   *
   * @param storage the storage the charter is bound to, never null
   * @return the next step, which still has to decide identity
   */
  public static Store of(Storage storage) {
    return new Store(Objects.requireNonNull(storage, "a charter is bound to a storage"));
  }

  Storage storage() {
    return storage;
  }

  AccessContextProvider currentAccess() {
    return currentAccess;
  }

  ObservationRegistry observations() {
    return observations;
  }

  /**
   * The same, with every operation observed through this registry.
   *
   * <p>One observation per operation, {@code occlude.operation}, tagged with the operation, the
   * portal, the outcome, the reason for a refusal and the class of anything thrown -- a timer and a
   * span through whatever handlers the registry has. Nothing a value, a label or an identity could
   * appear in. Without this, nothing is observed at all.
   *
   * @param observations the registry every operation is observed through, never null
   * @return these bindings, observed through that registry
   */
  public Bindings observedBy(ObservationRegistry observations) {
    return new Bindings(
        storage,
        currentAccess,
        Objects.requireNonNull(observations, "observed by some registry, even a no-op one"));
  }

  /** A store chosen, and identity not yet decided. */
  public static final class Store {

    private final Storage storage;

    private Store(Storage storage) {
      this.storage = storage;
    }

    /**
     * Where identity comes from, asked afresh on every operation.
     *
     * <p>Whatever this returns is taken as fact. It is the one input a caller cannot argue with,
     * which is why it must come from somewhere a caller does not control: a {@code ThreadLocal}, a
     * {@code ScopedValue}, Spring's {@code SecurityContextHolder}.
     *
     * @param currentAccess where the acting identity comes from, never null
     * @return bindings that ask that source on every operation
     */
    public Bindings withIdentity(AccessContextProvider currentAccess) {
      return new Bindings(
          storage,
          Objects.requireNonNull(currentAccess, "a charter is told where identity comes from"),
          ObservationRegistry.NOOP);
    }

    /**
     * No identity at all: every access is nobody in particular.
     *
     * <p>Said here rather than arrived at by leaving something out. A ceiling written as "unless
     * the context says otherwise" is wider than it looks when the context is always empty.
     *
     * @return bindings with no identity behind any access
     */
    public Bindings withoutIdentity() {
      return new Bindings(storage, AccessContextProvider.none(), ObservationRegistry.NOOP);
    }
  }
}
