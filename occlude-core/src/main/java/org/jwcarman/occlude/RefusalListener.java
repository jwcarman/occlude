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

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Told of every refusal, for an application that must know now and not at the next audit.
 *
 * <p>The trail calls it on the request thread, after the line is in the record and before the
 * operation returns. A listener never decides an outcome: the trail ignores any {@link
 * RuntimeException} that it throws. Give it with {@link Bindings#onRefusal}.
 *
 * <p>A listener that does slow work, such as a call to a pager, slows the request. Use {@link
 * #async} to move that work to an executor. Occlude does not throttle. A listener that calls a slow
 * service must throttle itself.
 */
@FunctionalInterface
public interface RefusalListener {

  /**
   * One refusal, after its line is in the record.
   *
   * @param event the refusal
   */
  void refused(RefusalEvent event);

  /**
   * The same listener, called on this executor and not on the request thread.
   *
   * <p>The caller owns the executor, its bounds and its shutdown. If the executor rejects the task,
   * for example because it is closed, the event is lost and the outcome does not change. The line
   * is in the record either way.
   *
   * <p>The listener then runs without the request thread's context: no caller transaction, no
   * security context and no logging context. The event carries the access context for this reason.
   *
   * @param executor where the listener runs, never null
   * @return a listener that gives each event to the executor
   */
  default RefusalListener async(Executor executor) {
    Objects.requireNonNull(executor, "async on some executor");
    return event -> {
      try {
        executor.execute(() -> refused(event));
      } catch (RejectedExecutionException _) {
        // A closed executor, at shutdown. The line is in the record, and that is the truth.
      }
    };
  }
}
