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

import java.util.Arrays;
import java.util.HexFormat;

/**
 * The last line of a trail, for publishing somewhere its database cannot reach.
 *
 * <p>Verification cannot notice lines cut from the end on its own: what remains is a valid trail
 * that simply stopped earlier. A head written down elsewhere -- a log line, a ticket, another
 * system -- and later checked with {@link JdbcStorage#stillHolds(TrailHead)} closes that. The
 * digest is a MAC under the store's root, so nobody without the root can forge one that checks.
 *
 * @param entryId the line's position in the trail
 * @param digest the line's digest
 */
public record TrailHead(long entryId, byte[] digest) {

  public TrailHead {
    digest = digest.clone();
  }

  @Override
  public byte[] digest() {
    return digest.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof TrailHead(long thatEntryId, byte[] thatDigest)
        && entryId == thatEntryId
        && Arrays.equals(digest, thatDigest);
  }

  @Override
  public int hashCode() {
    return 31 * Long.hashCode(entryId) + Arrays.hashCode(digest);
  }

  /** The form to write down: the position, and the digest in hex. */
  @Override
  public String toString() {
    return entryId + ":" + HexFormat.of().formatHex(digest);
  }
}
