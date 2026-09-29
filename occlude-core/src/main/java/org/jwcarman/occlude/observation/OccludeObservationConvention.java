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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;

/**
 * How an operation's observation is named and tagged.
 *
 * <p>Register one on the {@code ObservationRegistry} as a {@code GlobalObservationConvention} to
 * rename or reshape it, the way Micrometer expects. Whatever it adds is the application's decision;
 * {@link DefaultOccludeObservationConvention} adds only what describes the system.
 */
public interface OccludeObservationConvention
    extends ObservationConvention<OccludeObservationContext> {

  @Override
  default boolean supportsContext(Observation.Context context) {
    return context instanceof OccludeObservationContext;
  }
}
