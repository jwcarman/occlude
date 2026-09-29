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
 * What a charter keeps values in, for whoever writes a backend.
 *
 * <p>{@link org.jwcarman.occlude.storage.Storage} is the service-provider interface: it keeps bytes
 * and the record of what happened to them, and decides nothing. Every policy decision is made once,
 * in the charter's machinery, and shared by every implementation, so two backends cannot disagree
 * about who may see what. {@link org.jwcarman.occlude.storage.MemoryStorage} is the one kept here,
 * for tests and for proving a policy before a database is involved; {@code occlude-jdbc} is the
 * durable one.
 *
 * <p>An application declaring portals needs none of this. It needs it only to choose a backend and
 * to read the trail.
 */
package org.jwcarman.occlude.storage;
