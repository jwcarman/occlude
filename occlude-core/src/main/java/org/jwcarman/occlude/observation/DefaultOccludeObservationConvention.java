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

import io.micrometer.common.KeyValues;
import java.util.Locale;
import org.jwcarman.occlude.observation.OccludeObservationDocumentation.LowCardinalityKeys;

/**
 * Operation, portal, outcome, reason and error, and nothing else.
 *
 * <p>Every value is an enum or a name fixed when the charter was declared, so the number of series
 * is bounded by the charter itself.
 */
public class DefaultOccludeObservationConvention implements OccludeObservationConvention {

  /** What an absent reason or error reads as, so every series has the same keys. */
  private static final String NONE = "none";

  @Override
  public String getName() {
    return "occlude.operation";
  }

  @Override
  public String getContextualName(OccludeObservationContext context) {
    return "occlude " + operation(context) + " " + context.getPortal();
  }

  @Override
  public KeyValues getLowCardinalityKeyValues(OccludeObservationContext context) {
    return KeyValues.of(
        LowCardinalityKeys.OPERATION.withValue(operation(context)),
        LowCardinalityKeys.PORTAL.withValue(context.getPortal()),
        LowCardinalityKeys.OUTCOME.withValue(context.getOutcome().value()),
        LowCardinalityKeys.REASON.withValue(orNone(context.getReason())),
        LowCardinalityKeys.ERROR.withValue(orNone(context.getErrorType())));
  }

  private static String operation(OccludeObservationContext context) {
    return context.getOperation().name().toLowerCase(Locale.ROOT);
  }

  private static String orNone(String value) {
    return value == null ? NONE : value;
  }
}
