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
 * The authority to forget a value and everything derived from it.
 *
 * <p>Erasure used to be a policy on the charter and a method on the object that constructed it,
 * which made it the last authority that was checked rather than held. It is a portal like every
 * other now: declared with its policy, named in the record, and exercisable only by whoever was
 * handed it. An application that never declares one keeps a store nothing can erase from.
 *
 * <p>The policy sees the label of what is about to be destroyed as well as who is asking, because
 * who alone is not enough: a rule that only asks the caller's role lets one tenant's compliance
 * officer destroy another tenant's records.
 *
 * <p><b>Descendants go regardless.</b> The policy is asked about the root, and everything derived
 * from it is removed whether or not it is labelled more constrained -- which is what erasure means.
 * A value derived from two customers dies with either of them.
 *
 * <p>Minted during configuration, and obtainable only by being handed one.
 */
public interface Erasure {

  /**
   * Forgets the value and everything made from it, or refuses.
   *
   * @throws IllegalStateException if this erasure was never brought into force
   */
  Erased erase(Occluded<?> root);
}
