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

/**
 * What a sweep of every stored field found: what was changed, and what could not be read.
 *
 * <p>Two findings, kept apart because they mean different things. <b>Altered</b> is proof: the
 * field decrypted and is not what was signed for it, or is not a frame this store writes at all.
 * <b>Unreadable</b> is a fact about the keys at hand: the field would not decrypt, which is what a
 * destroyed key looks like and also what a damaged ciphertext looks like. Only whoever manages the
 * keys can tell which, so the sweep does not guess.
 *
 * @param alteredValues ids of values whose payload or label is not what was signed for them
 * @param unreadableValues ids of values whose payload or label would not decrypt
 * @param alteredLines entry ids of lines whose protected fields are not what was signed for them
 * @param unreadableLines entry ids of lines whose protected fields would not decrypt
 */
public record Sweep(
    List<String> alteredValues,
    List<String> unreadableValues,
    List<Long> alteredLines,
    List<Long> unreadableLines) {

  public Sweep {
    alteredValues = List.copyOf(alteredValues);
    unreadableValues = List.copyOf(unreadableValues);
    alteredLines = List.copyOf(alteredLines);
    unreadableLines = List.copyOf(unreadableLines);
  }

  /** Whether nothing was altered. Unreadable fields are not counted: see the class notes. */
  public boolean intact() {
    return alteredValues.isEmpty() && alteredLines.isEmpty();
  }
}
