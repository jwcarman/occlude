# Storage

Postgres. Everything a store keeps is **encrypted, and signed under a secret root** — neither is
optional, and a store refuses to be built without both:

```java
JdbcStorage storage = new JdbcStorageConfig()
    .dataSource(dataSource)
    .codecs(new JacksonCodecFactory(objectMapper))      // how values become bytes
    .encryptedWith(dataKeys)                            // a codec-crypto DataKeyProvider
    .rootedIn("prod-2026", secret)                      // what the digests are signed under
    .storage(axes);
```

`occlude-jdbc` contains no cryptography of its own. Every value, label, audit detail and audit
context goes through codec's `EnvelopeCodec` — a fresh AES-256-GCM data key per payload, wrapped
under your key-encryption key and recorded with its id — inside codec's `VersionedCodec`, so each
payload names the pipeline that wrote it and a later one can be introduced without rewriting what is
stored.

`dataKeys` is yours: a `JceDataKeyProvider` over keys you hold, or a provider backed by your KMS.
Rotating is adding a key and making it current; what the older one wrapped still decrypts under the
id recorded in its envelope.

**No compression**, deliberately. Compressing before encrypting makes a ciphertext's length depend
on what its plaintext says — the side channel CRIME and BREACH exploit — and a security library has
no size to save that is worth it.

## Roots

`rootedIn(name, secret)` names the secret the digests are computed under. There is no default: a
store used to be rooted in a published constant unless told otherwise, which made its record
forgeable by anyone who could write its tables. The name is stored with
each row, so rotating a root does not invalidate what was written under the last one — supply both
and old rows still verify.

**The secret is not in the database.** That is the whole point: an unkeyed chain catches a careless
edit and nothing else, because whoever removed a line could recompute everything after it. A row
naming a root nobody supplies is reported as broken rather than crashing the verifier.

## What is signed

The trail and the value graph are signed over **commitments to what things say**, never over their
ciphertext. Each value stores a keyed MAC over its payload and over its label, and each line one
over its detail, label and context — keyed by the root, so a small value cannot be found by
guessing, and bound to its row, so two rows saying the same thing commit differently. The digests
cover those commitments.

That keeps integrity and encryption apart, the way established audit systems do:

- **Encryption can be redone** — to retire a key, to move to a newer pipeline — and nothing signed
  changes.
- **A destroyed key erases what it protected without breaking the record.** The commitments stay, so
  the trail and the graph still verify; only the plaintext is gone.
- **Every read is checked.** A payload or label is decrypted and compared with its commitment before
  it is used, so a ciphertext copied in from another row — which would decrypt perfectly — is refused
  rather than served.

## Signing algorithm

HMAC-SHA-256 unless `.signedWith(MacAlgorithm.HMAC_SHA512)` (or `HMAC_SHA384`) says otherwise. Each
row records the one it was signed with, so a change applies to what comes next and everything older
still verifies. Only these three are ever accepted when reading a row back, so rewriting the column
cannot talk a verifier down to something weaker. Pair a change with a new root.

## Retiring a key

```java
storage.reencrypt();   // with the new key current and the old one still available
```

Decrypts every payload, label and audit field, checks each against its commitment, and encrypts it
again under the current key and pipeline version. Afterwards nothing needs the old key, and the
provider can drop it. A field that does not match its commitment stops the run instead of being
re-encrypted, because encrypting it afresh would make a swapped ciphertext look like this store wrote
it. Pages commit as they go; an interrupted run is finished by running it again.

## Anchoring

Verification cannot notice lines cut from the end: what remains is a trail that simply stopped
earlier. Publish the head somewhere the database cannot reach, and check it later:

```java
TrailHead head = storage.head().orElseThrow();   // write head.toString() down elsewhere
// ...
storage.stillHolds(head);                         // false once the trail was cut back past it
```

## What is in the clear

| column | stored |
|---|---|
| `occlude_value.payload` | encrypted |
| `occlude_value.label` | encrypted — a label can name a tenant |
| `occlude_audit.label`, `detail`, `context` | encrypted |
| `*_commitment`, `commitment`, `digest`, `mac`, `root_id` | in the clear — keyed MACs and their names, meaningless without the root |
| `occlude_audit.operation`, `outcome`, `reason`, `value_id` | in the clear |

The clear columns are the ones that name rules rather than values, so the trail stays queryable. An
audit nobody can query is a tape backup.

## Schema

`migrate()` creates the tables if they are not there. It is `CREATE TABLE IF NOT EXISTS` only —
nothing alters an existing table — and `.withoutMigration()` skips it for a database you manage
yourself.

The storage uses `pg_advisory_xact_lock`, recursive CTEs, `FOR SHARE` and `clock_timestamp()`, so
it is Postgres rather than "any JDBC database". Isolation is pinned to READ COMMITTED per
transaction and restored afterwards, because the chain's ordering depends on it.

## Verifying

```java
storage.firstBrokenEntry();   // check the chain first
storage.brokenValues();
storage.missingValues();
```

See [The Record](../concepts/the-record.md).

## In tests

`MemoryStorage` stores references in a map, does not encrypt, and does not survive a restart. It
holds the object you gave it rather than a copy, so hold immutable values and the difference never
shows.
