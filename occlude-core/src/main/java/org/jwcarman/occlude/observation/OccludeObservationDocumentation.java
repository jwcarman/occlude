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

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

/** Every observation Occlude makes, and every key it can carry. */
public enum OccludeObservationDocumentation implements ObservationDocumentation {

  /**
   * One operation at one portal: a conceal, reveal, derivation, question, erasure or inspection.
   */
  OPERATION {
    @Override
    public Class<? extends ObservationConvention<? extends Observation.Context>>
        getDefaultConvention() {
      return DefaultOccludeObservationConvention.class;
    }

    @Override
    public KeyName[] getLowCardinalityKeyNames() {
      return LowCardinalityKeys.values();
    }
  };

  /**
   * The only keys. None of them can carry a value, an identifier, a label or an identity.
   *
   * <p>Namespaced, as OpenTelemetry's semantic conventions ask of a library's own attributes, and
   * so nothing here collides with a framework's {@code outcome} on the same span. {@code
   * error.type} is the convention's own attribute for the class of error an operation ended with.
   */
  public enum LowCardinalityKeys implements KeyName {
    /** conceal, reveal, derive, query, erase or inspect. */
    OPERATION("occlude.operation"),
    /** The portal's declared name. */
    PORTAL("occlude.portal"),
    /** allowed, refused or failed. */
    OUTCOME("occlude.outcome"),
    /** The coarse reason code for a refusal, or none. */
    REASON("occlude.reason"),
    /** The fully-qualified class name of what was thrown, or none. */
    ERROR_TYPE("error.type");

    private final String key;

    LowCardinalityKeys(String key) {
      this.key = key;
    }

    @Override
    public String asString() {
      return key;
    }
  }
}
