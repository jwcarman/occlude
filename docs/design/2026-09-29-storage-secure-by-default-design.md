# Storage, secure by default

Status: phase 1 and phase 2 landed. Row binding of ciphertext (beyond what commitments give), length padding, forward-secure keys and Merkle proofs remain future work.

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

## What review changed (2026-09-29)

An adversarial review of phase 2 found, and these fix:

- **An unverifiable row matched a blanked digest.** A row naming an unknown MAC or root computed an
  empty digest, which a writer could match by clearing the stored one. Unverifiable is now broken,
  never "equal to empty".
- **Type, derivation and lineage were unchecked on read.** They now sit inside the commitments every
  read verifies: the payload's binds its type, the label's binds type, derivation and parents.
- **Tampering found on read left no record.** Storage throws `StorageIntegrityException`, and every
  operation records a refused line with reason `NOT_AS_SIGNED` before rethrowing. `sweep()` checks
  every ciphertext against its commitment and reports *altered* apart from *unreadable*.
- **Roots of any length were accepted.** A root must be at least 32 bytes, checked when the store is
  built and whenever a root is used.
- Constant-time comparisons in the verifiers; separate domain tags for value and line commitments;
  erase lines carry the root's label; `StoredValue` no longer prints its value.

A second review of those fixes found, and these fix: a sweep or read of a row naming a root nobody
supplies crashed rather than reporting it (now altered, and recorded); a fold over the same value
twice was stored with one lineage row but signed with two, so every read refused it (lineage is now
keyed by position); erasure trusted the lineage it walked, so a forged row could widen it (every
value it would reach is checked against its digest first, which needs no key); reads that would not
decrypt went unrecorded (now `StorageUnreadableException`, recorded as `UNREADABLE`); a stopped
reveal left an allowed line behind (the line is written only once the value is in hand); a record
that could not be written replaced the finding (it is attached as suppressed); and parents are read
in the same query as the row.

Still open: re-signing under a new root (a root, unlike a key, cannot yet be retired). Deferred past 0.1: it rewrites the whole chain and invalidates every published anchor, and deserves its own design. The storage guide states the limitation.

## Also phase 2

- **Binding a ciphertext to its row.** Commitments bound to the row already refuse a copied
  ciphertext at read time; `EnvelopeCodec`'s associated data would additionally make it fail to
  decrypt at all.
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
- **Heads are published** for anchoring, closing the truncation gap the record already documents:
  `head()` returns a `TrailHead` (the line's position and its digest, itself a MAC under the root)
  to write down somewhere the database cannot reach, and `stillHolds(head)` checks it later.
- **Reads are authenticated.** Because digests no longer cover ciphertext, a ciphertext copied in
  from another row would pass them; every read of a payload or label is checked against its
  commitment instead, and `reencrypt()` checks each field before rewriting it so it can never
  launder a swapped ciphertext. Forward-secure key
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
