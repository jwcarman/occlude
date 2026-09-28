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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the application knows about whoever is asking, at the moment they ask.
 *
 * <p><b>A charter never interprets this.</b> There is no principal, no role and no identity concept
 * in this library -- only a bag of strings an application fills in and its own ceiling functions
 * read. An application with no notion of identity contributes nothing and every sink ignores it.
 *
 * <p>It exists because of one case: a sink that is a <em>person</em>. Two approvers looking at the
 * same record are not necessarily entitled to see the same thing. Machine sinks almost always
 * ignore the context entirely -- a payment processor accepts cardholder data or it does not,
 * whoever asked.
 *
 * <p>It is also what an audit line records, which is the other reason it cannot be skipped.
 */
public record AccessContext(Map<String, String> attributes) {

  private static final AccessContext EMPTY = new AccessContext(Map.of());

  /**
   * Copied, in the order given. {@code Map.copyOf} would scramble it -- its iteration order is
   * randomized per JVM -- and this is what an audit line records, so a trail would name the same
   * caller differently from one run to the next.
   */
  public AccessContext {
    Map<String, String> copy = new LinkedHashMap<>();
    attributes.forEach(
        (key, value) ->
            copy.put(
                Objects.requireNonNull(key, "an attribute needs a name"),
                Objects.requireNonNull(value, "attribute '" + key + "' needs a value")));
    attributes = Collections.unmodifiableMap(copy);
  }

  /** Nobody in particular: the right answer for machine-to-machine work. */
  public static AccessContext empty() {
    return EMPTY;
  }

  public static AccessContext of(Map<String, String> attributes) {
    return new AccessContext(attributes);
  }

  public static AccessContext of(String key, String value) {
    return new AccessContext(Map.of(key, value));
  }

  public Optional<String> get(String key) {
    return Optional.ofNullable(attributes.get(key));
  }

  /** Whether an attribute has exactly this value, which is what a ceiling function usually asks. */
  public boolean has(String key, String value) {
    return value.equals(attributes.get(key));
  }
}
