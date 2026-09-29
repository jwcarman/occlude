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

package org.jwcarman.occlude.jdbc;

/**
 * Column names, read back through ResultSet often enough that each literal is worth naming once.
 */
final class Columns {

  static final String ENTRY_ID = "entry_id";
  static final String VALUE_ID = "value_id";
  static final String VALUE_TYPE = "value_type";
  static final String LABEL = "label";
  static final String DERIVATION = "derivation";
  static final String DIGEST = "digest";
  static final String PAYLOAD = "payload";
  static final String ROOT_ID = "root_id";
  static final String MAC = "mac";
  static final String COMMITMENT = "commitment";
  static final String PAYLOAD_COMMITMENT = "payload_commitment";
  static final String LABEL_COMMITMENT = "label_commitment";
  static final String DETAIL = "detail";
  static final String CONTEXT = "context";

  private Columns() {}
}
