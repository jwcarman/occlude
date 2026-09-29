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

  private Bindings(Storage storage, AccessContextProvider currentAccess) {
    this.storage = storage;
    this.currentAccess = currentAccess;
  }

  /** Binding to this store; identity is decided next. */
  public static Store of(Storage storage) {
    return new Store(Objects.requireNonNull(storage, "a charter is bound to a storage"));
  }

  Storage storage() {
    return storage;
  }

  AccessContextProvider currentAccess() {
    return currentAccess;
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
     */
    public Bindings withIdentity(AccessContextProvider currentAccess) {
      return new Bindings(
          storage,
          Objects.requireNonNull(currentAccess, "a charter is told where identity comes from"));
    }

    /**
     * No identity at all: every access is nobody in particular.
     *
     * <p>Said here rather than arrived at by leaving something out. A ceiling written as "unless
     * the context says otherwise" is wider than it looks when the context is always empty.
     */
    public Bindings withoutIdentity() {
      return new Bindings(storage, AccessContextProvider.none());
    }
  }
}
