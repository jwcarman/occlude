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

## What is in the clear

| column | stored |
|---|---|
| `occlude_value.payload` | encrypted |
| `occlude_value.label` | encrypted — a label can name a tenant |
| `occlude_audit.label`, `detail`, `context` | encrypted |
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
