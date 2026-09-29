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

import java.time.Instant;
import org.jwcarman.occlude.storage.AuditRecord;

/**
 * One line of the trail as it was recorded: where it sits, when the database witnessed it, and what
 * it says -- decrypted, and checked against what was signed for it.
 *
 * @param entryId the line's place in the trail
 * @param recordedAt when the database recorded it
 * @param line what it says, including the label and who was asking
 */
public record RecordedLine(long entryId, Instant recordedAt, AuditRecord line) {

  /**
   * Where and when, never what: the line carries a label and who was asking, decrypted, and a
   * record's generated {@code toString} would print both into whatever log this lands in.
   */
  @Override
  public String toString() {
    return "RecordedLine[entryId=" + entryId + ", recordedAt=" + recordedAt + "]";
  }
}
