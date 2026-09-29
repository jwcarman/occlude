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

import org.jwcarman.occlude.lattice.Label;

/**
 * What a storage holds about a value, read directly, for tests that assert state.
 *
 * <p>Deliberately beneath the charter. Reading a label through a charter is an {@link Inspection},
 * checked and recorded; a test that only wants to know what was written reads what was written, the
 * way the wiring that holds the storage could.
 */
final class Stored {

  private Stored() {}

  static Label label(Storage storage, Occluded<?> occluded) {
    return label(storage, occluded.id());
  }

  static Label label(Storage storage, String id) {
    return storage.metadata(id).orElseThrow().label();
  }

  static Lineage lineage(Storage storage, Occluded<?> occluded) {
    return storage.metadata(occluded.id()).orElseThrow().lineage();
  }
}
