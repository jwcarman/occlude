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
import java.util.Objects;
import java.util.Optional;

/**
 * What operating a store needs, and nothing that reads a value.
 *
 * <p>A {@link JdbcStorage} can hand over any value it holds, decrypted, with no ceiling asked and
 * no line written -- that is its job, and why application code never holds one. Whoever runs the
 * store still needs to verify it, anchor it and re-encrypt it, and none of that needs to see a
 * value. This is that half on its own: identifiers, counts and findings in, never plaintext out.
 *
 * <p>So this is what a scheduled verification job or an operations endpoint is given, rather than
 * the store.
 */
public final class StorageIntegrity {

  private final JdbcStorage storage;

  StorageIntegrity(JdbcStorage storage) {
    this.storage = Objects.requireNonNull(storage, "integrity is of some store");
  }

  /**
   * The digest of the last line written, for publishing somewhere the database cannot reach.
   *
   * @see JdbcStorage#head()
   */
  public Optional<TrailHead> head() {
    return storage.head();
  }

  /**
   * Whether the trail still contains a head published earlier, exactly as it was.
   *
   * @see JdbcStorage#stillHolds(TrailHead)
   */
  public boolean stillHolds(TrailHead anchor) {
    return storage.stillHolds(anchor);
  }

  /**
   * Where the trail stops agreeing with itself, or empty when it is intact.
   *
   * @see JdbcStorage#firstBrokenEntry()
   */
  public Optional<Long> firstBrokenEntry() {
    return storage.firstBrokenEntry();
  }

  /**
   * Every value the trail says should be here and is not.
   *
   * @see JdbcStorage#missingValues()
   */
  public List<String> missingValues() {
    return storage.missingValues();
  }

  /**
   * Every value whose digest no longer agrees with its own bytes and its ancestry.
   *
   * @see JdbcStorage#brokenValues()
   */
  public List<String> brokenValues() {
    return storage.brokenValues();
  }

  /**
   * Every stored field decrypted and checked against what was signed for it, reporting what was
   * altered apart from what was unreadable.
   *
   * @see JdbcStorage#sweep()
   */
  public Sweep sweep() {
    return storage.sweep();
  }

  /**
   * Every check at once: the trail, the values' digests, what is missing, every ciphertext, and the
   * head to anchor.
   *
   * <p>What a scheduled job runs. The head is read first, so a line written while the checks run is
   * simply after it, and an anchor taken from this report was verified up to where it points.
   */
  public IntegrityReport check() {
    Optional<TrailHead> head = storage.head();
    return new IntegrityReport(
        storage.firstBrokenEntry(),
        storage.brokenValues(),
        storage.missingValues(),
        storage.sweep(),
        head);
  }

  /**
   * Re-signs everything under the current root and MAC, which is what makes an old root retirable.
   *
   * @return what was re-signed, and the trail's head before and after
   * @see JdbcStorage#resign()
   */
  public Resigned resign() {
    return storage.resign();
  }

  /**
   * Re-encrypts everything under the current keys and pipeline, which is what makes a key
   * retirable.
   *
   * @return how many rows were rewritten
   * @see JdbcStorage#reencrypt()
   */
  public int reencrypt() {
    return storage.reencrypt();
  }
}
