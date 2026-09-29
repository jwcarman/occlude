# Operating a Store

Running a store is checking it, anchoring it, retiring keys and roots, and — when something is
found — reading the record back. None of it needs the store itself.

## What operations code is handed

The store hands over any value it holds, decrypted, with no ceiling asked and no line written, so
it goes to the charter and nothing else. Operations code gets one of two narrower things:

- **`StorageIntegrity`** — `storage.integrity()`, or the `StorageIntegrity` bean in Spring Boot.
  Checking, sweeping, anchoring, re-encrypting and re-signing. It returns identifiers, counts and
  findings, and reads no value.
- **`AuditTrail`** — `storage.trail()`. The record read back, decrypted: every line's label and who
  was asking. That is an authority in its own right, so hand it to investigation code only; the
  Spring starter registers it by name, hidden from injection by type.

## Checking

One call runs every verifier, and returns the head to anchor:

```java
IntegrityReport report = integrity.check();
report.intact();       // nothing provably altered, removed, restored or cut
report.unreadable();   // something would not decrypt with the keys at hand
report.head();         // write it down somewhere this database cannot reach
```

Or each on its own:

```java
integrity.firstBrokenEntry();   // a line edited, removed, reordered or replayed
integrity.brokenValues();       // a value edited, or descended from one
integrity.missingValues();      // the trail says it exists, and it is gone
integrity.unaccountedValues();  // it exists, and the trail never announced it or says it was erased
integrity.sweep();              // every ciphertext against its commitment, which needs the keys
```

The first four need only the root. `missingValues()` catches a deleted leaf, which nothing else
disagrees with; `unaccountedValues()` catches the reverse — a value restored from a copy taken
before it was erased, whose digest is genuine and which only the trail knows should be gone.

### Altered and unreadable

The sweep reports two kinds of finding, apart:

```java
Sweep sweep = integrity.sweep();
sweep.intact();            // nothing provably altered
sweep.alteredValues();     // decrypted, but not what was signed: proof of tampering
sweep.unreadableValues();  // would not decrypt with the keys at hand
```

*Unreadable* is what a destroyed key looks like, and also what a damaged ciphertext looks like; only
whoever manages the keys can tell which, so the sweep reports it apart from *altered* rather than
guessing. Somebody corrupting a field on purpose can choose which list it lands in, so reconcile
unreadable fields against the keys you actually destroyed and treat the rest as suspect. Reads of
either are refused, and recorded with reason `NOT_AS_SIGNED` or `UNREADABLE`.

A key service that cannot be reached is neither. It says nothing about the data, so a check or a
read during the outage fails with an `IllegalStateException`, like a database that is down, and
nothing is recorded against any value.

## Anchoring

Verification cannot notice lines cut from the end: what remains is a trail that simply stopped
earlier. Publish the head somewhere the database cannot reach, and check it later:

```java
String written = integrity.head().orElseThrow().toString();   // write this down elsewhere
// ...
integrity.stillHolds(TrailHead.parse(written));               // false once the trail was cut
```

Anything the database's writers cannot reach will do — a log shipped elsewhere, a ticket, another
system. The scheduled check logs the head on every run for exactly this.

## On a schedule

In Spring Boot, `occlude.integrity.interval` runs `check()` on its own thread:

```yaml
occlude:
  integrity:
    interval: 1h
```

Each run is an observation, `occlude.integrity`, tagged `occlude.integrity.result` = `intact`,
`unreadable`, `altered` or `failed` — alert on `altered`. What was found is logged as counts, never
ids, with the head at INFO so shipping that line somewhere the database cannot reach is your anchor.
Off unless set: a check reads and decrypts every row. A run that cannot finish is `failed`, and
concludes nothing either way.

There is deliberately no health indicator. Health drives liveness and readiness probes, and an
orchestrator restarting every instance because somebody edited one row turns a finding into an
outage. Tampering is a page, not a restart.

## Investigating

When a check finds something, the trail says what happened:

```java
AuditTrail trail = storage.trail();
trail.about(held.id());                  // everything that happened to one value
trail.between(yesterday, today);         // a window of time
trail.after(lastSeen, 500);              // the whole trail, a page at a time
```

Every line is decrypted and checked — its digest, its commitment, and that it follows the line
actually before it — so a line somebody changed is refused rather than reported as a fact.
`about()` finds lines by the value id they name, a column in the clear, so a rewritten id hides a
line from it; `check()` reports that line broken. A `RecordedLine` prints only where and when, never
what it says, so logging one does not copy the record somewhere unprotected.

## Retiring a key

```java
integrity.reencrypt();   // with the new key current and the old one still available
```

Decrypts every payload, label and audit field, checks each against its commitment, and encrypts it
again under the current key and pipeline version. Afterwards nothing needs the old key, and the
provider can drop it. A field that does not match its commitment stops the run instead of being
re-encrypted, because encrypting it afresh would make a swapped ciphertext look like this store
wrote it. Pages commit as they go; an interrupted run is finished by running it again. With keys per
tenant, each tenant's payloads move onto that tenant's current key.

## Retiring a root

A new root signs what comes next, and every row keeps the root it was signed under until it is
re-signed:

```java
JdbcStorage storage = new JdbcStorageConfig()
    // ...
    .rootedIn("prod-2027", id -> roots.get(id))   // the new root current, the old still supplied
    .storage(axes);

TrailHead now = storage.head().orElseThrow();
publish(now);                                     // first, so nothing after it is left to cut
Resigned resigned = storage.resign(now);          // every anchor passed must still hold
publish(resigned.after());                        // and afterwards none of them can
```

Once `resign()` returns nothing is signed under the old root, so drop it from the lookup and destroy
it. Before anything is signed again, every value and every line is checked under the root it names,
so something altered — or rewritten to claim the new root — is refused rather than laundered, and
one refusal leaves the store as it was: the run is a single transaction holding both locks, and
every write waits for it.

Three things to know before running it:

- **Pass your anchors.** Afterwards none of them can hold, because the lines they name carry new
  digests — so a trail cut back before re-signing would come out of it whole. An anchor protects
  the trail up to itself, which is why the example publishes the head immediately before.
  `resignWithoutAnchors()` is for a store that never published one, and says so.
- **Every stored field must decrypt**, because what is signed is a commitment to the plaintext. A
  value whose key was destroyed stops the run: erase it, then re-sign.
- **The same run changes the signing algorithm**, when only `signedWith(...)` changed.

## Offboarding a tenant

With keys per tenant: erase the tenant's values first, then destroy their key. Destroying the key
makes exactly their payloads unreadable — nobody else's — but a value whose key is gone can no
longer be re-encrypted or re-signed, and a sweep reports it unreadable until it is erased. Their
labels, and every line of the trail about them, stay under the shared keys: the record outlives any
tenant.
