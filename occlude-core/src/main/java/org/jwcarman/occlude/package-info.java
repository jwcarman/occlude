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
 * The charter every portal is declared on, and the engine behind them.
 *
 * <p>A {@link org.jwcarman.occlude.DefaultCharter} is constructed with the axes an application asks
 * about every value, declares each portal -- sources, sinks, derivations, questions, erasures,
 * inspections -- and is then bound, once, with {@link org.jwcarman.occlude.Bindings}: where values
 * are kept, where identity comes from, and what observes each operation. Binding brings every
 * portal into force at once; after it nothing further can be declared, and the charter itself is no
 * longer needed. {@link org.jwcarman.occlude.Charter#manifest()} renders what the declarations
 * permit, for a review.
 *
 * <p>Everything else here is package-private: the gate that decides, the trail that records, and
 * the operations each portal performs. There is no way to reach them but through a portal.
 */
package org.jwcarman.occlude;
