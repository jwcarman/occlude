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
/**
 * What application code holds: portals, the handles they hand out, and what comes back.
 *
 * <p>An {@link org.jwcarman.occlude.Occlude} takes a value in and hands back an {@link
 * org.jwcarman.occlude.Occluded} -- an opaque handle that travels anywhere, because holding one is
 * not permission to read it. A {@link org.jwcarman.occlude.Reveal} turns a handle back into a value
 * at one named sink; a {@link org.jwcarman.occlude.Derivation}, a {@link
 * org.jwcarman.occlude.Fold}, a {@link org.jwcarman.occlude.Query} and an {@link
 * org.jwcarman.occlude.Erasure} do the rest. Each is a capability: code can perform an operation
 * because something handed it the portal that performs it, and nothing can look one up.
 *
 * <p>A refusal is a result rather than an exception -- {@link org.jwcarman.occlude.Revealed},
 * {@link org.jwcarman.occlude.Derived}, {@link org.jwcarman.occlude.Answer}, {@link
 * org.jwcarman.occlude.Erased} -- each with {@code value()}, {@code succeeded()} and an {@code
 * orThrow()} for code that cannot go on without it.
 *
 * <p>Portals are declared on a charter, in {@code occlude-core}.
 */
package org.jwcarman.occlude;
