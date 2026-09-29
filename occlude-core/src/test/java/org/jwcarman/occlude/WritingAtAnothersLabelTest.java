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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.occlude.lattice.Axis;
import org.jwcarman.occlude.lattice.Ceiling;
import org.jwcarman.occlude.lattice.Constraint;
import org.jwcarman.occlude.lattice.Label;
import org.jwcarman.occlude.storage.MemoryStorage;

/**
 * Writing at somebody else's label, which used to be refused and is now unsayable.
 *
 * <p>This test was written against a policy. Code acting for acme called {@code hold} with a label
 * naming globex, and {@code mayHold} turned it away. Both are gone: nothing in the API takes a
 * label, so the forgery has no way to be expressed, and the policy that caught it has nothing left
 * to police.
 *
 * <p>What replaced it is stronger and is what these tests now assert. A source carries its own
 * label, decided when it was declared, and reads the tenant from the access rather than the caller.
 * Two tenants using the same source get two different labels and neither of them chose.
 */
@DisplayName("Writing at somebody else's label")
class WritingAtAnothersLabelTest {

  private static final OccludedType<Note> NOTE_TYPE = OccludedType.of(Note.class);

  enum Integrity {
    ENDORSED,
    UNENDORSED
  }

  interface Value {}

  record Note(String text) implements Value {}

  private static final Axis<String> TENANT = Axis.matching("tenant");
  private static final Axis<Integrity> INTEGRITY =
      Axis.ladder("integrity", Integrity.ENDORSED, Integrity.UNENDORSED);

  private final AtomicReference<AccessContext> edge = new AtomicReference<>(AccessContext.empty());

  private final MemoryStorage storage = new MemoryStorage();

  private final DefaultCharter config = new DefaultCharter(TENANT, INTEGRITY);

  /** One source, used by whoever is acting. It is the access that decides, never the caller. */
  private final Occlude<Note> notes =
      config.source(
          "notes",
          NOTE_TYPE,
          ctx ->
              ctx.get("tenant")
                  .map(tenant -> Label.of(TENANT, tenant))
                  .orElseGet(Label::nothing)
                  .with(INTEGRITY, Integrity.ENDORSED));

  private final Reveal<Note> reporting =
      config
          .sink(
              "reporting",
              ctx ->
                  ctx.get("tenant")
                      .map(tenant -> Ceiling.of(TENANT, Constraint.atMost(tenant)))
                      .orElseGet(() -> Ceiling.of(TENANT, Constraint.any()))
                      .with(INTEGRITY, Constraint.atMost(Integrity.UNENDORSED)),
              NOTE_TYPE)
          .reading(NOTE_TYPE);

  {
    config.bind(Bindings.of(storage).withIdentity(edge::get));
  }

  // read `.tenant()` off the stored label is re-expressed against Label.toString(), which is
  // documented for rendering only. Deviation from PRESERVE-exactly, flagged for James: there is no
  // other supported way to check what a stored label says on a given axis.
  @Test
  @DisplayName("is refused, so a forgery never becomes somebody else's fact")
  void is_refused() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Occluded<Note> written = notes.occlude(new Note("globex owes us 1,000,000"));

    // Acme wrote it and acme owns it. There was no argument through which to claim otherwise.
    assertThat(Stored.label(storage, written.id()).says(TENANT, "acme")).isTrue();

    // And globex does not read it as its own.
    edge.set(AccessContext.of(Map.of("tenant", "globex")));
    assertThat(reporting.reveal(written).succeeded()).isFalse();
  }

  @Test
  @DisplayName("while writing at your own is ordinary")
  void your_own_label_is_ordinary() {
    edge.set(AccessContext.of(Map.of("tenant", "acme")));

    Occluded<Note> mine = notes.occlude(new Note("our own note"));

    assertThat(storage.contains(mine.id())).isTrue();
    assertThat(reporting.reveal(mine).value()).contains(new Note("our own note"));
  }
}
