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
package org.jwcarman.occlude.jdbc;

import java.util.List;
import java.util.Optional;

/**
 * Everything verification can say about a store, from one pass.
 *
 * <p>Four checks, because each sees what the others cannot: the chain of lines, the digests of the
 * values, the values the trail says should exist and do not, and every ciphertext against what was
 * signed for it. With the trail's head, to write down somewhere the database cannot reach.
 *
 * @param firstBrokenEntry where the trail stops agreeing with itself, if it does
 * @param brokenValues values whose digest no longer agrees with their bytes and ancestry
 * @param missingValues values the trail announced, never erased, and which are not there
 * @param sweep every ciphertext checked against its commitment
 * @param head the last line, when anything has been recorded
 */
public record IntegrityReport(
    Optional<Long> firstBrokenEntry,
    List<String> brokenValues,
    List<String> missingValues,
    Sweep sweep,
    Optional<TrailHead> head) {

  public IntegrityReport {
    brokenValues = List.copyOf(brokenValues);
    missingValues = List.copyOf(missingValues);
  }

  /**
   * Whether nothing was provably altered, removed or cut. Unreadable fields are not counted: they
   * are what a destroyed key looks like, and only whoever manages the keys can tell.
   */
  public boolean intact() {
    return firstBrokenEntry.isEmpty()
        && brokenValues.isEmpty()
        && missingValues.isEmpty()
        && sweep.intact();
  }

  /** Whether anything would not decrypt with the keys at hand. */
  public boolean unreadable() {
    return !sweep.unreadableValues().isEmpty() || !sweep.unreadableLines().isEmpty();
  }
}
