# Storage, secure by default

Status: phase 1 landed; phase 2 (the record signed over plaintext commitments, algorithm agility, signed heads) agreed, not started.

## What prompted this

Occlude's aim is security, not storage size, and `occlude-jdbc` did not reflect that:

- **Encryption was optional.** `storedThrough(...)` took whatever byte pipeline the application
  composed, and `storedPlainly()` wrote values, labels and audit context in the clear. The storage
  guide recommended compressing first.
- **Compression leaked.** `Compression.whenItHelps` compressed before encrypting and marked which it
  did. Compressed length depends on content, which is the side channel CRIME and BREACH exploit; its
  own Javadoc admitted the marker byte and length "say roughly how compressible a value was".
- **The audit chain's key was public by default.** `JdbcStorageConfig` rooted every store in the
  constant `"occlude"` unless told otherwise. A keyed chain is tamper-evident only while its key is
  secret, so by default it was forgeable by anyone who can write the tables — its own Javadoc says so.
- **Nothing recorded how a payload was protected**, so the pipeline could never change without
  rewriting everything.

## Decisions

**Encryption is mandatory.** A store is built with a codec-crypto `DataKeyProvider` and encrypts
every value, label, audit detail and audit context with codec's `EnvelopeCodec`: a fresh AES-256-GCM
data key per payload, wrapped under the provider's current key-encryption key. There is no plaintext
mode and no escape hatch. Tests that need a database use a key generated in the test; unit tests use
`MemoryStorage`, which never touches this pipeline.

**No compression.** Size is not the goal, and compress-then-encrypt is a known length leak.
`Compression` is removed, along with the guide's advice to compress conditionally.

**The pipeline is versioned.** codec-versioned's `VersionedCodec` is outermost, so each payload
names the pipeline that wrote it and a later pipeline can be introduced without rewriting what is
stored. Version numbers are fixed forever: **version 1 is `EnvelopeCodec` over the configured
provider**. A future version (padding, a new algorithm) is added as 2 and switched to with codec's
two-phase `.writing(...)` rollout.

**A root is mandatory.** `rootedIn(...)` must be called; there is no default secret.

**Keys come from codec's `DataKeyProvider` (option C).** `occlude-jdbc` requires one and never reads
configuration. The Spring starter adds a properties-backed `JceDataKeyProvider` for applications that
want one without code, and backs off when the application defines its own `DataKeyProvider` bean
(a KMS, Vault):

```yaml
occlude:
  keys:
    current: k2
    keks:
      k1: ${OCCLUDE_KEK_1}   # base64 AES-256, from the environment or a secret store
      k2: ${OCCLUDE_KEK_2}
  roots:
    current: r1
    secrets:
      r1: ${OCCLUDE_ROOT_1}
```

Startup fails with a message naming what is missing when an application uses JDBC storage and
supplies neither keys nor a root. Nothing ever generates or ships a key.

Rotation needs no migration: add a key, make it current, and older payloads still decrypt under the
key id recorded in their envelope. Roots already rotate the same way.

## Crypto agility

codec is agile where it matters for values: an envelope records its format version, algorithm and
key id, and the wrapped data key carries its wrap scheme. With `VersionedCodec` outermost, occlude
can change the whole pipeline. Two gaps are occlude's, and are phase 2:

1. **The MAC algorithm is not recorded.** Digests are `HmacSHA256`, hard-coded. A root should name
   its algorithm as well as its secret, so moving algorithms is a root rotation.
2. **Nothing retires a key.** Retiring one means re-encrypting what it protects (decode, then encode
   under the current version and key). A cheap re-wrap is impossible by design: `EnvelopeCodec`
   authenticates its whole header, wrapped key included, as GCM associated data. Re-encrypting audit
   lines breaks the chain, which signs their encrypted bytes; phase 2 below resolves it.

## Also phase 2

- **Binding a ciphertext to its row.** `EnvelopeCodec` supports associated data; binding each
  payload to its value id would make a ciphertext copied onto another row fail to decrypt rather
  than wait for a digest check to notice.
- **Length padding.** Ciphertext length tracks plaintext length. Padding to size buckets would hide
  it, and fits as a pipeline version 2.

## Phase 2: the record, the standard way (agreed 2026-09-29)

The trail and the value digests are signed over **ciphertext** today, which ties integrity to
encryption: re-encrypting anything breaks the chain, so a key can never be retired. Established
practice keeps the two layers apart — CloudTrail's log-file integrity validation signs digests of
the log while storage encryption sits underneath it, and the tamper-evident logging literature
(Schneier–Kelsey; Crosby–Wallach history trees; Certificate Transparency) signs the log's content,
not its storage form. Occlude will do the same:

- **Sign keyed commitments to the plaintext.** Each line and value stores `HMAC(root, canonical
  plaintext)` for its encrypted fields, and the chain and value digests cover those commitments and
  the clear columns, never ciphertext. Keyed, because an unkeyed hash of a small value — a role, a
  last-4 — is brute-forced in moments.
- **Encryption becomes freely replaceable.** A re-encryption job can decode and re-encode under the
  current pipeline version and key, and every digest still verifies. That is what makes retiring a
  key possible.
- **Erasure by key destruction still works.** Commitments survive a destroyed key, so the chain still
  verifies; only matching ciphertext to its commitment becomes impossible, which is the intent.
- **The MAC algorithm is recorded with the root**, so a root is a secret and an algorithm, and moving
  algorithms is a root rotation.
- **Signed heads are published** for anchoring, closing the truncation gap the record already
  documents: the head, signed, written somewhere the database cannot reach. Forward-secure key
  evolution and Merkle-tree proofs are the natural next steps and are not in this phase.

## What changes

- `JdbcStorageConfig`: `encryptedWith(DataKeyProvider)` and `rootedIn(...)` are required.
  `storedThrough`, `storedPlainly` and the default root are removed.
- `StorageCodec` and `Compression` are removed. `occlude-jdbc` depends on `codec-crypto` and
  `codec-versioned` at compile scope; it still contains no cryptography of its own.
- Spring: the JDBC auto-configuration takes a `DataKeyProvider` bean and a root from properties;
  a properties-backed provider is contributed when no bean exists.
- The example, the tests and the storage guide follow.

Nothing has been published, so rows written by earlier builds are not migrated.
