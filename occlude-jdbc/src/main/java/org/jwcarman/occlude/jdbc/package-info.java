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
 * Durable storage in Postgres, encrypted and signed.
 *
 * <p>{@link org.jwcarman.occlude.jdbc.JdbcStorageConfig} builds a {@link
 * org.jwcarman.occlude.jdbc.JdbcStorage}, and refuses until it has keys to encrypt with and a
 * secret root to sign under -- there is no plaintext mode and no default secret. Every value and
 * every line of the record is encrypted through codec's envelope, committed to under the root, and
 * chained, so what the tables hold can be verified without trusting whoever can write them.
 *
 * <p>The store is the charter's, not application code's: it hands over whatever it holds. What
 * operating it needs is {@link org.jwcarman.occlude.jdbc.StorageIntegrity} -- checking, sweeping,
 * anchoring, re-encrypting, re-signing -- which reads no value; what an investigation needs is
 * {@link org.jwcarman.occlude.jdbc.AuditTrail}, which reads the record back.
 */
package org.jwcarman.occlude.jdbc;
