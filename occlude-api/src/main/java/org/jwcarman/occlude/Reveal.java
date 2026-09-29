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
 * The authority to reveal a value: to take plaintext back out, at one particular ceiling and for
 * one particular type.
 *
 * <p>The counterpart to {@link Occlude}. A value goes in through one and comes back out through the
 * other, and each is named for what it does rather than for where it sits in a dataflow -- two
 * flows cross here, plaintext and occluded references, so "source" and "sink" cannot say which one
 * they belong to. Those words name the places at either end instead: a {@link Sink} is where a
 * value leaves to, and this is one door into it.
 *
 * <p><b>Declared during configuration, obtained only by being handed one.</b> Before this existed,
 * a caller named the door it wanted, and the factory for those names was public -- so any class
 * could construct the name of any door and present it, and the only thing between an arbitrary
 * caller and cardholder plaintext was a ceiling re-checked on every call. Holding this is now the
 * authority, and there is no method that trades a name for one.
 *
 * <p>The ceiling is still checked, and still may depend on who is asking: this is a door, not a
 * bypass. What changed is that reaching the door at all is no longer something a caller can decide
 * for itself.
 *
 * <p><b>Narrowed by type as well as by label</b>, because a label alone is a coarse instrument. The
 * day somebody holds a session token at the same label as an invoice -- not maliciously, just
 * without thinking about it -- a door that accepts any type reads it. Saying what comes out is a
 * second, independent narrowing, and the authority this confers is smaller for it.
 *
 * <p>It also moves the type check left. {@code Reveal<Invoice>.reveal(occludedMail)} does not
 * compile, where naming a sink at a call site accepted any occluded reference and refused at
 * runtime. The runtime check remains and is not redundant: {@link Occluded#of} will give any id any
 * type you ask for, so what the store actually wrote is still the only ground truth, and it is
 * still what gets compared.
 *
 * @param <T> the type of value this door will hand over, and no other
 */
public interface Reveal<T> {

  /**
   * What comes out of it.
   *
   * @return the type this door hands over, and the name it is stored under
   */
  OccludedType<T> type();

  /**
   * Gives back the value an occluded reference stands in for, if its label is at or below what this
   * door accepts.
   *
   * <p>Returns a refusal rather than throwing, because being turned away is an ordinary outcome
   * that calling code is expected to handle: show the reference instead, ask for approval, take the
   * other branch.
   *
   * <p>A refusal says which door said no and a coarse reason. It does not say what the value was
   * labelled or what this door accepts -- that detail goes to the audit record, because a refusal
   * message that explains itself is an oracle: code that may not read a value could still learn its
   * classification by asking often enough.
   *
   * @param occluded the reference to the value wanted
   * @return the value, or the refusal
   * @throws IllegalStateException if this door was never brought into force
   */
  Revealed<T> reveal(Occluded<T> occluded);
}
