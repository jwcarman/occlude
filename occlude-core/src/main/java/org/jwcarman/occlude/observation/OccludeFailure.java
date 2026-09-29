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
package org.jwcarman.occlude.observation;

import java.io.Serial;

/**
 * What an observation is told failed, in place of what actually did.
 *
 * <p>Tracing marks a span as an error only when its observation is given an exception, and then it
 * records that exception's message and stack trace -- where a storage exception's message names a
 * value. So the observation is given this instead: its message is the real exception's class name
 * and nothing else, with no cause and no stack trace. The span still says it failed, and says what
 * kind of failure; it cannot say what about.
 */
public final class OccludeFailure extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public OccludeFailure(Throwable actual) {
    super(actual.getClass().getName(), null, false, false);
  }
}
