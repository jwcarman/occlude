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

/**
 * What a {@link Storage} holds is not what it signed.
 *
 * <p>Thrown when a stored field fails the check a store makes before handing it over -- a
 * ciphertext copied in from another row, a label or type rewritten underneath its commitment. Not a
 * refusal: nobody asked for anything they may not have. Something changed what the store holds
 * without going through it, and the operation that found it records the fact before passing it on.
 */
public class StorageIntegrityException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public StorageIntegrityException(String message) {
    super(message);
  }
}
