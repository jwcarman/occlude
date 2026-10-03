# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Refusal events.** A `RefusalListener` on `Bindings.onRefusal(...)` is told of every refusal,
  after its line is in the record. The `RefusalEvent` carries the time, the operation, the portal,
  the reason as a `RefusalReason`, the value id and the access context. It never carries the value, the label, the
  detail or an exception. A listener that throws changes nothing. `RefusalListener.async(Executor)`
  moves the work off the request thread. The Spring starter publishes each refusal as an
  application event, synchronously; add `@Async` to a listener that does slow work. The design is
  `docs/design/2026-10-03-refusal-events-design.md`.

### Changed

- **Every refused line gives a code.** Five kinds of refused line gave prose as their reason. They
  now give `NO_SUCH_VALUE` (an erasure of a value nobody holds, and a derivation whose result could
  not be written), `NOT_PERMITTED`, `SOURCE_CANNOT_LABEL` and `INCOMPLETE_LABEL`. The prose is in
  the detail. Lines written before keep what they said, and their signatures stay valid. A query or
  an alert on the old text must change.

- **A function that throws is `FAILED`, not a decline.** `Derived.Reason` and `Answer.Reason` each
  gain `FAILED`, the reason when a derivation's or a query's own function throws after it was
  handed the plaintext. A derivation reported this as `DECLINED` and a query as
  `NOT_AVAILABLE_HERE`, so a fault looked like a decision until someone read the record. Code that
  switches over either enum without a default needs the new case. Records already written keep the
  reason they were written with; nothing stored changes.

## [0.1.0] - 2026-09-29

The first release. From here on the stored format -- the schema, the digests and the envelope -- is
a baseline: a later release that changes any of it says so here and ships the way to move a store
forward.

### Added

- **Occluded references.** `Occluded<T>` stands in for a sensitive value and discloses nothing but
  its identifier; turning it back into a value is the one checked operation, and it always names
  where the value is going.
- **A lattice of labels and ceilings.** Values carry a label over the axes an application declares
  (`Axis.matching` for identities, `Axis.ladder`
  for ordered levels); every sink declares a ceiling. Derivation joins parents' labels,
  so an ordinary derivation cannot weaken one, and the one operation that can lower a label is a
  named, declared derivation.
- **Portals as capabilities.** A `DefaultCharter` declares every door -- `Occlude`, `Reveal`,
  `Derivation`, `Fold`, `Query`, `Erasure`, `Inspection` -- and hands each back as the object able to
  perform it. There is no lookup by name. Binding the charter to storage and identity, once, is what
  activates them.
- **Sealed results.** `Revealed`, `Derived`, `Answer`, `Erased` and `Inspected` report a refusal as an
  ordinary outcome with a reason code that names a rule and never a value; `orThrow()` for code that
  cannot go on without it.
- **A manifest.** Every declaration, readable at startup and pasted into a review, with findings for
  what is provably unreachable.
- **`occlude-jdbc`: storage in PostgreSQL.** Payloads, labels and protected audit fields encrypted
  through codec's versioned envelope, with keys per tenant available through `keyedBy`. An
  HMAC-chained audit trail, keyed digests over the value graph, and keyed commitments to every field,
  all rooted in a secret the database does not hold.
- **Integrity checking.** `StorageIntegrity.check()` verifies the trail, the value graph and every
  ciphertext, and reports what is missing, unaccounted for, altered or unreadable; it streams, so a
  large store checks in constant memory. `head()` and `stillHolds()` anchor the trail against
  truncation; `reencrypt()` and `resign()` retire keys and roots. `AuditTrail` reads the record back.
- **Observations.** Every operation is a Micrometer observation, `occlude.operation`, tagged with the
  operation, portal, outcome, reason and error class -- never a value.
- **Spring Boot.** `occlude-spring-boot-starter` configures storage and the charter from `occlude.*`
  properties, schedules the integrity check with `occlude.integrity.interval`, and exposes the
  manifest at `/actuator/charter`.
- **`occlude-bom`** for aligning module versions.

### Requirements

- Java 25
- PostgreSQL, for `occlude-jdbc`
- Spring Boot 4.1, for the starter

[Unreleased]: https://github.com/jwcarman/occlude/compare/0.1.0...HEAD
[0.1.0]: https://github.com/jwcarman/occlude/releases/tag/0.1.0
