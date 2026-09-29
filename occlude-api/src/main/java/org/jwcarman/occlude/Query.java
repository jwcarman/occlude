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
 * The authority to learn one fact about a value without the value leaving.
 *
 * <p>Boolean on purpose. A question that could return the customer's email has not protected the
 * customer's email; one that can only answer whether a given address matches has. The literature
 * calls the general problem inference control, and the classic result is the tracker attack of
 * Denning, Denning and Schwartz (1979): a sequence of individually harmless aggregate answers
 * reconstructs the record they were meant to hide. Dinur and Nissim (2003) proved the general case.
 *
 * <p>So a single answer is bounded at one bit, and nothing here bounds a thousand of them. The
 * ceiling decides who may ask and the audit line records that they did; counting is the
 * application's job, and it is a real job.
 *
 * <p>What makes counting tractable is the shape of the question. Equality leaks almost nothing --
 * it confirms a guess somebody already had. A prefix, a range or a comparison leaks enormously,
 * because it turns repetition into binary search. Declaring the first kind and not the second is
 * worth more than any budget.
 *
 * <p>One bit only if nothing else leaves. The function is handed the value and whatever it is asked
 * against, so asking against something that can hold or pass things on -- a {@code Consumer}, a
 * {@code StringBuilder}, a list -- gives it somewhere to put the value. Ask against plain values;
 * the manifest lists any question that is not, as a {@code not-a-plain-value} finding.
 *
 * <p>Declared during configuration, and obtainable only by being handed one.
 *
 * @param <I> the type of value this can be asked about
 * @param <Q> what the question is asked against: a plain value, part of what a review reads
 */
public interface Query<I, Q> {

  /**
   * Answers, or refuses. The answer is a bit; what was asked never appears in the record.
   *
   * @param about the value asked about
   * @param against what it is asked against
   * @return the one bit, or the refusal
   */
  Answer ask(Occluded<I> about, Q against);

  /**
   * What a query actually does: looks at the value, and returns one bit.
   *
   * @param <I> the type of value this can be asked about
   * @param <Q> what the question is asked against
   */
  @FunctionalInterface
  interface Asking<I, Q> {
    /**
     * The question itself.
     *
     * @param value the plaintext being asked about
     * @param against what it is asked against: a plain value, part of what a review reads
     * @param context who is asking
     * @return the one bit
     */
    boolean test(I value, Q against, AccessContext context);
  }
}
