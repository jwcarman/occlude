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

import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.occlude.storage.StorageIntegrityException;

/**
 * Re-signs everything a store holds under its current root and MAC, so an old root can be retired.
 *
 * <p>Both signed structures chain, and that shapes everything here. A value's digest covers its
 * parents' digests, so re-signing a value re-signs everything derived from it, parents first. A
 * line's digest and commitment cover the line before it, so once one line is re-signed, every line
 * after it is too.
 *
 * <p>Nothing is re-signed that has not first been checked under the root it was signed with --
 * otherwise a row somebody had altered would come out the other side signed as though this store
 * wrote it. A row can only be checked against its parents' and its predecessor's digests as they
 * were, so those are kept as the rows are rewritten. It all happens in one transaction holding both
 * locks: nothing can change between a row being checked and being re-signed, and one row that fails
 * leaves the whole store exactly as it was.
 *
 * <p>Ciphertext is untouched. What is signed is a commitment to the plaintext, so every field must
 * decrypt for its commitment to be made again -- which means a field whose key was destroyed stops
 * re-signing, named, rather than being carried across unverified.
 */
final class Resigning {

  private final Transactions transactions;
  private final Signer signer;
  private final Fields fields;

  Resigning(Transactions transactions, Signer signer, Fields fields) {
    this.transactions = transactions;
    this.signer = signer;
    this.fields = fields;
  }

  Resigned resign() {
    return transactions.inTransactionReturning(
        "could not re-sign the store",
        connection -> {
          Transactions.lockLineageExclusively(connection);
          Transactions.lockTrailHead(connection);
          Optional<TrailHead> before = head(connection);
          int values = resignValues(connection);
          int lines = resignLines(connection);
          return new Resigned(values, lines, before, head(connection));
        });
  }

  private static Optional<TrailHead> head(Connection connection) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT entry_id, digest FROM occlude_audit ORDER BY entry_id DESC LIMIT 1");
        ResultSet rows = statement.executeQuery()) {
      return rows.next()
          ? Optional.of(
              new TrailHead(rows.getLong(Columns.ENTRY_ID), rows.getBytes(Columns.DIGEST)))
          : Optional.empty();
    }
  }

  private boolean current(String root, String mac) {
    return signer.rootId().equals(root) && signer.mac().jcaName().equals(mac);
  }

  // ------------------------------------------------------------------ values

  private static final String GRAPH =
      "SELECT v.value_id, v.root_id, v.mac, " + Fields.PARENTS_OF_V + " FROM occlude_value v";

  private static final String VALUE =
      """
      SELECT v.value_id, v.value_type, v.derivation, v.payload, v.label, v.payload_commitment,
             v.label_commitment, v.digest, v.root_id, v.mac,
             (SELECT array_agg(parent_id ORDER BY position) FROM occlude_lineage l
               WHERE l.child_id = v.value_id) AS parents,
             (SELECT array_agg(p.digest ORDER BY l.position)
                FROM occlude_lineage l LEFT JOIN occlude_value p ON p.value_id = l.parent_id
               WHERE l.child_id = v.value_id) AS parent_digests
        FROM occlude_value v WHERE v.value_id = ?
      """;

  private static final String UPDATE_VALUE =
      """
      UPDATE occlude_value
         SET payload_commitment = ?, label_commitment = ?, digest = ?, root_id = ?, mac = ?
       WHERE value_id = ?
      """;

  private record Node(boolean current, List<String> parents) {}

  private int resignValues(Connection connection) throws SQLException {
    List<String> order = parentsFirst(graph(connection));
    Map<String, byte[]> wasDigest = new HashMap<>();
    Map<String, byte[]> nowDigest = new HashMap<>();
    try (PreparedStatement select = connection.prepareStatement(VALUE);
        PreparedStatement update = connection.prepareStatement(UPDATE_VALUE)) {
      for (String id : order) {
        select.setString(1, id);
        try (ResultSet rows = select.executeQuery()) {
          rows.next();
          resignValue(rows, update, wasDigest, nowDigest);
        }
      }
    }
    return order.size();
  }

  private Map<String, Node> graph(Connection connection) throws SQLException {
    Map<String, Node> graph = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(GRAPH);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        graph.put(
            rows.getString(Columns.VALUE_ID),
            new Node(
                current(rows.getString(Columns.ROOT_ID), rows.getString(Columns.MAC)),
                Fields.parentsIn(rows)));
      }
    }
    return graph;
  }

  /**
   * Every value that must be re-signed -- under an old root, or derived from one that is -- with
   * each after all of its parents. A parent that is not stored is left for the check to refuse.
   */
  private static List<String> parentsFirst(Map<String, Node> graph) {
    Map<String, List<String>> children = new HashMap<>();
    graph.forEach(
        (id, node) ->
            node.parents()
                .forEach(p -> children.computeIfAbsent(p, k -> new ArrayList<>()).add(id)));
    Set<String> stale = new HashSet<>();
    Deque<String> spreading = new ArrayDeque<>();
    graph.forEach(
        (id, node) -> {
          if (!node.current()) {
            spreading.add(id);
          }
        });
    while (!spreading.isEmpty()) {
      String id = spreading.removeFirst();
      if (stale.add(id)) {
        spreading.addAll(children.getOrDefault(id, List.of()));
      }
    }
    Map<String, Integer> waitingOn = new HashMap<>();
    Deque<String> ready = new ArrayDeque<>();
    for (String id : stale) {
      int waiting =
          (int) graph.get(id).parents().stream().distinct().filter(stale::contains).count();
      waitingOn.put(id, waiting);
      if (waiting == 0) {
        ready.add(id);
      }
    }
    List<String> order = new ArrayList<>();
    while (!ready.isEmpty()) {
      String id = ready.removeFirst();
      order.add(id);
      for (String child : children.getOrDefault(id, List.of())) {
        // Every child of a stale value is stale itself: that is how staleness spread.
        if (waitingOn.merge(child, -1, Integer::sum) == 0) {
          ready.add(child);
        }
      }
    }
    if (order.size() != stale.size()) {
      throw new StorageIntegrityException(
          "the lineage of this store has a cycle, which nothing this store writes can make;"
              + " nothing was re-signed");
    }
    return order;
  }

  private void resignValue(
      ResultSet rows,
      PreparedStatement update,
      Map<String, byte[]> wasDigest,
      Map<String, byte[]> nowDigest)
      throws SQLException {
    String id = rows.getString(Columns.VALUE_ID);
    String type = rows.getString(Columns.VALUE_TYPE);
    String derivation = rows.getString(Columns.DERIVATION);
    List<String> parents = Fields.parentsIn(rows);
    // One entry per lineage row, in the same order as the parents: a parent that is not stored is
    // there as a null, which the check below refuses.
    List<byte[]> storedParentDigests = digests(rows.getArray("parent_digests"));
    List<byte[]> was = new ArrayList<>();
    List<byte[]> now = new ArrayList<>();
    for (int i = 0; i < parents.size(); i++) {
      byte[] stored = storedParentDigests.get(i);
      was.add(wasDigest.getOrDefault(parents.get(i), stored));
      now.add(nowDigest.getOrDefault(parents.get(i), stored));
    }
    byte[] storedDigest = rows.getBytes(Columns.DIGEST);
    Optional<byte[]> expected =
        was.stream().anyMatch(Objects::isNull)
            ? Optional.empty()
            : signer.digestIfSigned(
                rows.getString(Columns.ROOT_ID),
                rows.getString(Columns.MAC),
                id,
                type,
                new Signer.ValueCommitments(
                    rows.getBytes(Columns.PAYLOAD_COMMITMENT),
                    rows.getBytes(Columns.LABEL_COMMITMENT)),
                derivation,
                was);
    if (expected.isEmpty() || !MessageDigest.isEqual(expected.get(), storedDigest)) {
      throw new StorageIntegrityException(
          id + " is not what was signed for it, so nothing was re-signed");
    }
    byte[] payload = fields.payloadOf(rows);
    byte[] label = fields.labelPlaintextOf(rows, parents);
    Signer.ValueCommitments commitments =
        new Signer.ValueCommitments(
            signer.valueCommitment(
                signer.rootId(),
                signer.mac(),
                id,
                Signer.PAYLOAD,
                Signer.payloadFacts(type),
                payload),
            signer.valueCommitment(
                signer.rootId(),
                signer.mac(),
                id,
                Signer.LABEL,
                Signer.labelFacts(type, derivation, parents),
                label));
    byte[] digest =
        signer.digestOf(signer.rootId(), signer.mac(), id, type, commitments, derivation, now);
    update.setBytes(1, commitments.payload());
    update.setBytes(2, commitments.label());
    update.setBytes(3, digest);
    update.setString(4, signer.rootId());
    update.setString(5, signer.mac().jcaName());
    update.setString(6, id);
    update.executeUpdate();
    wasDigest.put(id, storedDigest);
    nowDigest.put(id, digest);
  }

  private static List<byte[]> digests(Array stored) throws SQLException {
    return stored == null
        ? List.of()
        : Arrays.stream((Object[]) stored.getArray()).map(byte[].class::cast).toList();
  }

  // ------------------------------------------------------------------ lines

  private static final int PAGE = 500;

  private static final String LINES =
      """
      SELECT entry_id, recorded_at, operation, value_id, target, outcome, reason, previous, digest,
             root_id, mac, commitment, detail, label, context
        FROM occlude_audit WHERE entry_id >= ? ORDER BY entry_id LIMIT ?
      """;

  private static final String UPDATE_LINE =
      """
      UPDATE occlude_audit
         SET previous = ?, commitment = ?, digest = ?, root_id = ?, mac = ?
       WHERE entry_id = ?
      """;

  private int resignLines(Connection connection) throws SQLException {
    Long first = firstStaleLine(connection);
    if (first == null) {
      return 0;
    }
    Walk walk = new Walk(digestBefore(connection, first));
    int resigned = 0;
    long from = first;
    try (PreparedStatement select = connection.prepareStatement(LINES);
        PreparedStatement update = connection.prepareStatement(UPDATE_LINE)) {
      select.setInt(2, PAGE);
      while (true) {
        select.setLong(1, from);
        int read = 0;
        try (ResultSet rows = select.executeQuery()) {
          while (rows.next()) {
            read++;
            from = rows.getLong(Columns.ENTRY_ID) + 1;
            resignLine(rows, update, walk);
            resigned++;
          }
        }
        if (read < PAGE) {
          return resigned;
        }
      }
    }
  }

  /**
   * The chain as the walk moves along it: where it stood before the line being re-signed, as it was
   * and as it now is. Advanced once per line, so the next line is checked against its old
   * predecessor and signed after its new one.
   */
  private static final class Walk {
    private byte[] was;
    private byte[] now;

    private Walk(byte[] before) {
      this.was = before;
      this.now = before;
    }

    private void advance(byte[] stored, byte[] resigned) {
      this.was = stored;
      this.now = resigned;
    }
  }

  private Long firstStaleLine(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT min(entry_id) FROM occlude_audit WHERE root_id <> ? OR mac <> ?")) {
      statement.setString(1, signer.rootId());
      statement.setString(2, signer.mac().jcaName());
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        long first = rows.getLong(1);
        return rows.wasNull() ? null : first;
      }
    }
  }

  private static byte[] digestBefore(Connection connection, long entry) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT digest FROM occlude_audit WHERE entry_id < ? ORDER BY entry_id DESC LIMIT 1")) {
      statement.setLong(1, entry);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getBytes(1) : null;
      }
    }
  }

  private void resignLine(ResultSet rows, PreparedStatement update, Walk walk) throws SQLException {
    long entry = rows.getLong(Columns.ENTRY_ID);
    byte[] previous = rows.getBytes("previous");
    Instant recordedAt = rows.getTimestamp("recorded_at").toInstant();
    Signer.LineFacts facts =
        new Signer.LineFacts(
            rows.getString("operation"),
            rows.getString(Columns.VALUE_ID),
            rows.getString("target"),
            rows.getString("outcome"),
            rows.getString("reason"));
    byte[] storedDigest = rows.getBytes(Columns.DIGEST);
    Optional<byte[]> expected =
        Arrays.equals(previous, walk.was)
            ? signer.lineDigestIfSigned(
                rows.getString(Columns.ROOT_ID),
                rows.getString(Columns.MAC),
                previous,
                recordedAt,
                facts,
                rows.getBytes(Columns.COMMITMENT))
            : Optional.empty();
    if (expected.isEmpty() || !MessageDigest.isEqual(expected.get(), storedDigest)) {
      throw new StorageIntegrityException(
          "line "
              + entry
              + " of the trail is not what was signed for it, so nothing was re-signed");
    }
    Fields.Line line = fields.lineOf(rows);
    byte[] commitment =
        signer.lineCommitment(
            signer.rootId(),
            signer.mac(),
            walk.now,
            recordedAt,
            line.detail(),
            line.label(),
            line.context());
    byte[] digest =
        signer.lineDigest(signer.rootId(), signer.mac(), walk.now, recordedAt, facts, commitment);
    update.setBytes(1, walk.now);
    update.setBytes(2, commitment);
    update.setBytes(3, digest);
    update.setString(4, signer.rootId());
    update.setString(5, signer.mac().jcaName());
    update.setLong(6, entry);
    update.executeUpdate();
    walk.advance(storedDigest, digest);
  }
}
