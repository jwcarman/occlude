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
/**
 * Spring Boot auto-configuration for Occlude.
 *
 * <p>Declare an {@code Axes} bean and the starter constructs the charter, binds it once the context
 * has finished making singletons, and wires the JDBC store from {@code occlude.*} configuration:
 * keys, roots, and an optional scheduled integrity check. Identity comes from an {@code
 * AccessContextProvider} bean, and every operation is observed through Spring Boot's {@code
 * ObservationRegistry}. The store and the audit trail are registered by name and hidden from
 * injection by type; what operations code needs is the {@code StorageIntegrity} bean.
 */
package org.jwcarman.occlude.spring;
