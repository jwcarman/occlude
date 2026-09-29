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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * How a store writes: one transaction per act, and the locks that order acts against each other.
 */
final class Transactions {

  private final DataSource dataSource;

  Transactions(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * One write, in a transaction this library chooses the isolation of.
   *
   * <p>READ COMMITTED is pinned rather than inherited, and the chain depends on it. A REPEATABLE
   * READ transaction fixes its snapshot at its first statement, which here is the one taking the
   * advisory lock -- so an appender queues correctly, then reads the head as it was before it
   * queued, and several lines commit naming the same predecessor. Nobody has attacked anything and
   * the trail reports itself broken, which is worse than useless: it teaches operators that the
   * verifier cries wolf. Pools configured REPEATABLE READ are ordinary, so this cannot be a hope.
   *
   * <p>Both the isolation level and the auto-commit flag are put back, because the connection goes
   * back to a pool that lent it on the terms it had.
   */
  void inTransaction(String what, SqlWork work) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      if (!autoCommit) {
        refuseSomebodyElsesTransaction(connection);
      }
      int isolation = connection.getTransactionIsolation();
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      boolean committed = false;
      try {
        work.run(connection);
        connection.commit();
        committed = true;
      } finally {
        // Throwable, not RuntimeException. Restoring auto-commit COMMITS whatever is in flight,
        // so an Error on its way out -- an OutOfMemoryError inside an application-supplied codec,
        // say -- would have committed the value rows without the audit line that has to accompany
        // them. Rolling back first is what makes "restore the connection" safe to do afterwards.
        if (!committed) {
          rollbackQuietly(connection);
        }
        connection.setAutoCommit(autoCommit);
        connection.setTransactionIsolation(isolation);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(what, e);
    }
  }

  /**
   * Refuses a connection handed over in the middle of somebody else's transaction.
   *
   * <p>Every act is its own transaction, committed before it returns: the record is evidence of
   * what happened, and must not roll back with whatever the caller does next. A data source that
   * hands out the connection of a transaction already open -- a transaction-aware proxy, a pool
   * returning a connection its last user left mid-transaction -- would have this commit that
   * transaction's work early. A pool configured with auto-commit off is ordinary, so off alone
   * proves nothing; a transaction that has already written does, and is refused.
   */
  private static void refuseSomebodyElsesTransaction(Connection connection) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT pg_current_xact_id_if_assigned() IS NOT NULL");
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      if (rows.getBoolean(1)) {
        throw new IllegalStateException(
            "the data source handed over a connection with somebody else's transaction in"
                + " progress. occlude commits every act on its own, so it would have committed"
                + " that work early; give it a plain DataSource, not one that hands out the"
                + " current transaction's connection");
      }
    }
    // Asking opened a transaction of its own, and nothing in it has written, so ending it undoes
    // nothing -- and lets the isolation level be set for the act that follows.
    connection.rollback();
  }

  /** The same, for a write that has something to report back. */
  <T> T inTransactionReturning(String what, SqlAnswer<T> work) {
    AtomicReference<T> answer = new AtomicReference<>();
    inTransaction(what, connection -> answer.set(work.run(connection)));
    return answer.get();
  }

  /**
   * Rolls back without replacing the exception that is already on its way out.
   *
   * <p>A rollback that throws used to mask the original failure, which is the one worth reading:
   * the reason the work failed explains the rollback, never the other way round.
   */
  static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException _) {
      // Nothing to do with it that would not hide why we are here.
    }
  }

  @FunctionalInterface
  interface SqlWork {
    void run(Connection connection) throws SQLException;
  }

  @FunctionalInterface
  interface SqlAnswer<T> {
    T run(Connection connection) throws SQLException;
  }

  /** One name every appender waits on, so the trail has one order. */
  private static final long TRAIL_LOCK = 0x10C_A0D17L;

  /**
   * What deriving and erasing contend on, so that one cannot miss the other.
   *
   * <p>A share lock on the parent rows is not enough, and the way it fails is worth writing down.
   * Under READ COMMITTED a blocked DELETE resumes with the snapshot its statement began with. So an
   * erasure could take its snapshot -- in which the parent has no children -- block on a
   * derivation's share lock, and then resume and delete only what it saw. The child committed in
   * between is invisible to it: a value derived from erased data survives, no ERASE line names it,
   * and its parent is gone, so the value verifier reports it broken forever.
   *
   * <p>Shared for deriving and exclusive for erasing. Any number of derivations proceed at once; an
   * erasure waits for the ones in flight and shuts out the ones that would start. That is the right
   * way round: deriving is common and must not block itself, erasing is rare and must be complete.
   * Taken before the trail lock in both paths, so the two are always acquired in the same order and
   * cannot deadlock against each other.
   */
  private static final long LINEAGE_LOCK = 0x10C_11AE9L;

  /** Waits for every derivation in flight, and holds off the ones that have not started. */
  static void lockLineageExclusively(Connection connection) throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
      lock.setLong(1, LINEAGE_LOCK);
      lock.execute();
    }
  }

  /** Shared, so derivations never wait on one another -- only on an erasure. */
  static void lockLineageShared(Connection connection) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock_shared(?)")) {
      lock.setLong(1, LINEAGE_LOCK);
      lock.execute();
    }
  }

  /**
   * The line this one follows, and the moment this one is being written.
   *
   * <p>Both come back from the database, and the time is read <i>while the lock is held</i>. That
   * is the whole point of taking it here rather than in the caller: the lock is what decides this
   * line's position in the chain, so a clock sampled after it cannot disagree with that position.
   * {@code now()} would not do -- it is the transaction's start time, fixed before the lock was
   * ever asked for, so two appenders could still land in an order their timestamps deny.
   *
   * <p>It costs nothing. The lock had to be taken anyway, and a statement that takes it can return
   * a column while it is at it.
   */
  record Predecessor(byte[] digest, Instant recordedAt) {
    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Predecessor(byte[] thatDigest, Instant thatRecordedAt))) {
        return false;
      }
      return Arrays.equals(digest, thatDigest) && recordedAt.equals(thatRecordedAt);
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(digest), recordedAt);
    }

    @Override
    public String toString() {
      return "Predecessor[digest=" + Arrays.toString(digest) + ", recordedAt=" + recordedAt + "]";
    }
  }

  static Predecessor lockTrailHead(Connection connection) throws SQLException {
    Instant recordedAt;
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?), clock_timestamp() AS now")) {
      lock.setLong(1, TRAIL_LOCK);
      try (ResultSet rows = lock.executeQuery()) {
        rows.next();
        recordedAt = rows.getTimestamp("now").toInstant();
      }
    }
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT digest FROM occlude_audit ORDER BY entry_id DESC LIMIT 1");
        ResultSet rows = statement.executeQuery()) {
      return new Predecessor(rows.next() ? rows.getBytes(Columns.DIGEST) : null, recordedAt);
    }
  }
}
