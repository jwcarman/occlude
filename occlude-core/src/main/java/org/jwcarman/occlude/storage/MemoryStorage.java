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
package org.jwcarman.occlude.storage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jwcarman.codec.TypeRef;

/**
 * Storage in a map.
 *
 * <p>For tests, single-process tools, and proving a policy before a database is involved. It does
 * not encrypt and does not survive a restart.
 *
 * <p><b>It stores references, not copies.</b> A durable store serialises on the way in and hands
 * back a fresh object every time; this one does not, so a caller that mutates a value after holding
 * it changes what was stored under a label chosen for what it used to be. Hold immutable values and
 * the difference never shows.
 */
public final class MemoryStorage implements Storage {

  /** Creates an empty store. */
  public MemoryStorage() {
    // Its collections start empty where they are declared.
  }

  private final List<AuditRecord> audit = Collections.synchronizedList(new ArrayList<>());

  /**
   * Everything recorded so far, oldest first. The in-memory equivalent of the audit table.
   *
   * @return the trail, oldest first
   */
  public List<AuditRecord> audit() {
    return List.copyOf(audit);
  }

  /**
   * Just the lines for one kind of operation, oldest first.
   *
   * @param operation the kind of operation to select
   * @return the lines for that operation, oldest first
   */
  public List<AuditRecord> audit(AuditRecord.Operation operation) {
    return audit().stream().filter(entry -> entry.operation() == operation).toList();
  }

  /**
   * Forgets the trail so far.
   *
   * <p>For tests that want to watch one operation without the setup that preceded it. A durable
   * store has no equivalent, and deliberately: the trail is the thing that must not be erasable.
   */
  public void clearAudit() {
    audit.clear();
  }

  @Override
  public void append(AuditRecord entry) {
    audit.add(entry);
  }

  private final Map<String, StoredValue> values = new ConcurrentHashMap<>();

  @Override
  public void put(String id, StoredValue value, AuditRecord entry) {
    append(entry);
    values.put(id, value);
  }

  @Override
  public Optional<StoredMetadata> metadata(String id) {
    return Optional.ofNullable(values.get(id))
        .map(stored -> new StoredMetadata(stored.type().name(), stored.label(), stored.lineage()));
  }

  @Override
  public <T> Optional<T> value(String id, TypeRef<T> type) {
    return Optional.ofNullable(values.get(id)).map(stored -> type.rawClass().cast(stored.value()));
  }

  /**
   * Everything currently held, for tests that need to prove something was not stored.
   *
   * @return the identifier of every value held
   */
  public Set<String> everything() {
    return Set.copyOf(values.keySet());
  }

  @Override
  public boolean contains(String id) {
    return values.containsKey(id);
  }

  @Override
  public List<String> erase(String root, Function<String, AuditRecord> lineFor) {
    Set<String> doomed = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      String next = pending.removeFirst();
      if (!doomed.add(next)) {
        continue;
      }
      List<String> children = new ArrayList<>();
      values.forEach(
          (id, stored) -> {
            if (stored.lineage().parents().contains(next)) {
              children.add(id);
            }
          });
      pending.addAll(children);
    }
    // Removing and checking in one step, per value: another eraser may get to one first.
    List<String> removed = doomed.stream().filter(id -> values.remove(id) != null).toList();
    removed.forEach(id -> append(lineFor.apply(id)));
    return removed;
  }
}
