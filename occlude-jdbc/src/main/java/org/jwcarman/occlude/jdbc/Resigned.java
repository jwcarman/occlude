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

import java.util.Optional;

/**
 * What re-signing a store under its current root did.
 *
 * <p>Every head published before re-signing stops holding: the lines it pointed at now carry
 * digests under the new root. So both heads are here -- keep {@code before} with the anchors
 * already published, and publish {@code after} in their place.
 *
 * @param values how many values were re-signed
 * @param lines how many lines of the trail were re-signed
 * @param before the head as it was, under the roots it was signed with
 * @param after the head now, under the current root
 */
public record Resigned(
    int values, int lines, Optional<TrailHead> before, Optional<TrailHead> after) {}
