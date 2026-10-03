# Occlude rules

## Tone and voice — ASD-STE100, about 80% of the way

Write prose that people read in Simplified Technical English (ASD-STE100), softened
to about 80% of the specification. This is an experiment: James wants to know if it
makes output easier to read. If a rule makes a sentence less clear, break that rule.

**Where it applies:** messages and reports to James, docs pages, READMEs, javadoc
and code comments, commit message bodies, CHANGELOG entries, design docs, specs and
plans.
**Where it does not apply:** code, identifiers, log output, quotations, and
measured data. Do not rewrite text that you quote.

**The rules, adapted:**
- Write short sentences. Procedural sentences: 20 words or fewer. Descriptive
  sentences: 25 words or fewer. One instruction in each procedural sentence.
- Write short paragraphs. One topic in each paragraph, six sentences or fewer.
- Use the active voice. Use the imperative for steps ("Run the gate", not "The
  gate should be run").
- Use simple tenses: present, past and future. Do not use "-ing" words as nouns or
  as the main verb.
- Use one word for one meaning, and use it every time. The approved dictionary is
  Occlude's own terms: charter, portal, source, sink, occluded reference, label,
  axis, ceiling, lowering, derivation, fold, query, reveal, erasure, inspection,
  refusal, reason, detail, access context, binding, manifest, store. The audit
  trail is "the trail", and one entry in it is "a line"; do not call the trail "the
  record". This dictionary wins over STE's word list. Do not use a synonym for
  variety.
- Keep the articles ("the", "a"). Do not write telegraphic text.
- Do not use idioms, metaphors, slang or phrasal verbs when a single verb exists
  ("start", not "kick off"; "find", not "figure out").
- Use vertical lists for sequences and for more than three items.
- Put a warning or a condition before the instruction it controls ("If the build
  hangs after an interface change, install the changed module first").
- Make negatives clear and specific. Say what to do, not only what not to do.

**Never at the cost of truth:** do not cut a caveat, a measurement, a source or a
"not verified" mark to make a sentence shorter. Precision wins over brevity. When
STE has no word for a technical idea, use the correct technical term and do not
explain it with a vague one.
