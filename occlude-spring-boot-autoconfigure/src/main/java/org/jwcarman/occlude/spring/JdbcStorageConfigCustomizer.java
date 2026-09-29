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
package org.jwcarman.occlude.spring;

import org.jwcarman.occlude.jdbc.JdbcStorageConfig;

/**
 * Adjusts how the JDBC store is built, before the starter builds it.
 *
 * <p>The way to reach what configuration properties do not cover -- keys per tenant, most often:
 *
 * <pre>{@code
 * @Bean
 * JdbcStorageConfigCustomizer tenantKeys(TenantKms kms) {
 *   return config -> config.keyedBy(TENANT, kms::providerFor);
 * }
 * }</pre>
 *
 * <p>Applied in order, after the starter has set the data source, codecs, keys and root, so a
 * customizer may change any of them. It cannot build the store: the starter does that, once, and
 * keeps it where application code cannot reach it.
 */
@FunctionalInterface
public interface JdbcStorageConfigCustomizer {

  void customize(JdbcStorageConfig config);
}
