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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The name values of this type are stored under.
 *
 * <p>Optional, and read by {@link OccludedType#of(Class)}. Without it that uses the kebab-cased
 * simple name -- {@code DisputeClaim} becomes {@code dispute-claim}. With it, this wins; and a name
 * passed explicitly to {@link OccludedType#of(String, Class)} wins over both.
 *
 * <p><b>Whatever it says is permanent.</b> The name is written beside every value of this type and
 * compared when one is read back, so changing it orphans everything already stored. It reads like a
 * label and behaves like a schema version. If you expect the shape to change, say so here from the
 * start: {@code @OccludedName("billing.invoice/v1")}.
 *
 * <p>This is the one annotation this library offers, and taking it is a choice: name every type
 * explicitly with {@link OccludedType#of(String, Class)} instead, and nothing of ours appears in
 * your domain model.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OccludedName {

  /** The stored name. */
  String value();
}
