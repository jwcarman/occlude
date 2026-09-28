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
import java.util.function.Function;
import org.jwcarman.occlude.lattice.Ceiling;

/** Ways to declare a {@link SinkSpec}, which is always done at wiring and never at a call site. */
public final class Sinks {

  private Sinks() {}

  /** A sink that accepts the same thing regardless of who is asking: nearly all of them. */
  public static SinkSpec fixed(String name, Ceiling ceiling) {
    Objects.requireNonNull(name, "a sink needs a name");
    Objects.requireNonNull(ceiling, "a sink needs a ceiling");
    return new SinkSpec() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Ceiling ceiling(AccessContext context) {
        return ceiling;
      }
    };
  }

  /**
   * A sink whose ceiling depends on who is asking.
   *
   * <p>For people. An approval card may show a finance approver more than it shows anyone else, and
   * that is a decision only the application can make, from context only the application supplied.
   */
  public static SinkSpec varying(String name, Function<AccessContext, Ceiling> ceiling) {
    Objects.requireNonNull(name, "a sink needs a name");
    Objects.requireNonNull(ceiling, "a sink needs a ceiling");
    return new SinkSpec() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Ceiling ceiling(AccessContext context) {
        return Objects.requireNonNull(
            ceiling.apply(context), "a ceiling function must not return null");
      }
    };
  }
}
