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
package org.jwcarman.occlude.storage;

/**
 * A {@link Storage} could not read back what it holds.
 *
 * <p>Thrown when a stored field will not decrypt with the keys at hand. Not proof of tampering, the
 * way {@link StorageIntegrityException} is: a destroyed key looks exactly like this, and so does a
 * damaged ciphertext. Only whoever manages the keys can tell which, so it is its own finding, and
 * the operation that met it records the fact before passing it on.
 */
public class StorageUnreadableException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public StorageUnreadableException(String message, Throwable cause) {
    super(message, cause);
  }
}
