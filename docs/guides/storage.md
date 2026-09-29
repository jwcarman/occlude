# Storage

Postgres 13 or later. Everything a store keeps is **encrypted, and signed under a secret root** —
neither is optional, and a store refuses to be built without both:

```java
JdbcStorage storage = new JdbcStorageConfig()
    .dataSource(dataSource)
    .codecs(new JacksonCodecFactory(objectMapper))      // how values become bytes
    .encryptedWith(dataKeys)                            // a codec-crypto DataKeyProvider
    .rootedIn("prod-2026", secret)                      // what the digests are signed under
    .storage(axes);
```

In Spring Boot the starter builds it from configuration instead — see
[Spring Boot](spring-boot.md#storage).

`occlude-jdbc` contains no cryptography of its own. Every value, label, audit detail and audit
context goes through codec's `EnvelopeCodec` — a fresh AES-256-GCM data key per payload, wrapped
under your key-encryption key and recorded with its id — inside codec's `VersionedCodec`, so each
payload names the pipeline that wrote it and a later one can be introduced without rewriting what is
stored.

**No compression**, deliberately. Compressing before encrypting makes a ciphertext's length depend
on what its plaintext says — the side channel CRIME and BREACH exploit — and a security library has
no size to save that is worth it.

**The store is for the charter, not for your code.** `value()` and `metadata()` hand over what it
holds with no ceiling asked and no line written, because the portals that call them already did
both. Bind the charter to it and keep it there. Running it — checking, sweeping, anchoring,
re-encrypting, re-signing, reading the trail back — is [Operating a Store](operating.md).

## Keys

`dataKeys` is yours: a `JceDataKeyProvider` over keys you hold, or a provider backed by your KMS.
Rotating is adding a key and making it current; what the older one wrapped still decrypts under the
id recorded in its envelope, and [re-encrypting](operating.md#retiring-a-key) retires it.

## Keys per tenant

`keyedBy(axis, keysFor)` encrypts each value's payload under the keys of whatever its label says on
that axis — typically a tenant's own, so a customer's data can sit under a key the customer controls:

```java
JdbcStorage storage = new JdbcStorageConfig()
    // ...
    .encryptedWith(sharedKeys)                          // everything else
    .keyedBy(TENANT, tenant -> kms.providerFor(tenant)) // a payload labelled tenant=acme: acme's keys
    .storage(axes);
```

The key follows the label, not whoever is asking: the label is the value's own statement of whose it
is, fixed when it was stored, and the same thing isolation is defined on. `keysFor` is asked once per
tenant, the first time one is needed, and every key id it hands out must be unique across tenants,
because a payload is read back by the id recorded in its envelope. A tenant it returns no keys for
reads as unreadable, the way a destroyed key does everywhere else.

**Only payloads.** A value's label is read to learn whose keys open the payload beside it, so it could
never be read if it were under those same keys; labels stay under the shared keys, and so does every
line of the trail, so revoking a tenant's key never costs you the record. A value whose label says
nothing on the axis, or mixes several tenants, uses the shared keys too. Keying by a ladder works
the same way, by rung — cardholder data under its own key hierarchy, say.

[Offboarding a tenant](operating.md#offboarding-a-tenant) is erasing their values, then destroying
their key.

## Roots

`rootedIn(name, secret)` names the secret the digests are computed under. It must be at least 32
bytes, random, and kept outside the database: the record is exactly as hard to forge as the root is
to guess. There is no default: a store rooted in a published constant would have a record anyone
who can write its tables could forge.

**The secret is not in the database.** That is the whole point: an unkeyed chain catches a careless
edit and nothing else, because whoever removed a line could recompute everything after it. A row
naming a root nobody supplies is reported as broken rather than crashing the verifier.

The name is stored with each row, so a new root does not invalidate what the last one signed —
supply both, and old rows still verify. [Re-signing](operating.md#retiring-a-root) moves everything
onto the new one, so the old can be destroyed.

## Signing algorithm

HMAC-SHA-256 unless `.signedWith(MacAlgorithm.HMAC_SHA512)` (or `HMAC_SHA384`) says otherwise. Each
row records the one it was signed with, so a change applies to what comes next and everything older
still verifies. Only these three are ever accepted when reading a row back, so rewriting the column
cannot talk a verifier down to something weaker. Pair a change with a new root.

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
  rather than served. The commitments also cover the value's type, what made it and from which
  parents, so none of those can be rewritten underneath it either. A read that finds tampering throws
  `StorageIntegrityException`, and the operation records a refused line with reason `NOT_AS_SIGNED`
  before the exception reaches the caller.

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

`migrate()` creates the tables if they are not there, when the store is built. It is
`CREATE TABLE IF NOT EXISTS` only — nothing alters an existing table — and `.withoutMigration()`
skips it for a database you manage yourself (`occlude.migrate: false` in Spring Boot).

The storage uses `pg_advisory_xact_lock`, recursive CTEs, `FOR SHARE`, `clock_timestamp()` and
`pg_current_xact_id_if_assigned()`, so it is Postgres rather than "any JDBC database". Every act is
its own transaction, isolation is pinned to READ COMMITTED and restored afterwards, because the
chain's ordering depends on it — and it never joins yours, for the reasons in
[What Occlude Does Not Do](../limits.md#it-never-joins-your-transaction-on-purpose).

For tests, `MemoryStorage` keeps values in a map, unencrypted — see [Testing](testing.md).
