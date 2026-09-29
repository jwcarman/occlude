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

/**
 * The authority to read what a value is labelled and where it came from, without the value.
 *
 * <p>This used to be two methods on the charter, answered with no ceiling and no line in the
 * record, which made it look harmless: nothing moved anywhere. But a label names a tenant or a
 * project codeword. Storage encrypts it, and a refusal deliberately does not repeat it, because
 * code that may not read a value could otherwise learn its classification by asking often enough.
 * An ungated read of the same label contradicted both.
 *
 * <p>So it is a portal like every other: declared with a ceiling, checked against the value's
 * label, and recorded whether it is answered or refused.
 *
 * <p>Minted during configuration, and obtainable only by being handed one. It lives beside {@link
 * Charter} rather than with the other portals because what it hands back is a {@link
 * org.jwcarman.occlude.lattice.Label}, which the api module does not know about.
 */
public interface Inspection {

  /**
   * The value's label and lineage, or a refusal.
   *
   * @throws IllegalStateException if this inspection was never brought into force
   */
  Inspected inspect(Occluded<?> occluded);
}
