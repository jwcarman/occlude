# What Occlude Does Not Do

Knowing what a security library declines to promise is how you find out whether it fits. None of
what follows is a bug; each is a documented boundary, and several have tests that pin the boundary
in place.

## What it defends against, and what it leaves to you

Three rules decide where the line is:

1. **The safe path is the default.** Values are encrypted and signed with no way to turn it off,
   authority is handed out rather than looked up, and weakening anything takes an explicit act.
2. **Weakening is visible.** What makes protection weaker shows up at startup or in the
   [manifest](guides/reviewing.md) — a lowering, a question asked with something that could carry
   the value, a door nothing can reach — never silently.
3. **It does not defend against deliberate misuse from inside the process.** Code in the same JVM
   that means to get around it can: by reflection, with the `DataSource` and the keys from
   configuration, from a heap dump. Occlude makes the honest path easy and the dishonest one
   deliberate and reviewable; it is not a sandbox.

It supports whatever an application's labels mean without deciding them. Nothing in it knows what a
tenant is: axes are the application's, and whether one must always be said is too — see
[labels](concepts/labels.md#isolating-tenants).

## It decides disclosure, not action

Occlude answers *may this value be seen here*. It does not answer *should this happen*.

A model that can name an occluded reference can ask a tool to act on it, and if the ceiling admits the value,
occlude says yes — correctly. Whether a refund of that size, to that account, at that moment, should
occur is an authorisation question, and the amount is not a labelled value at all.

Pair occlude with whatever authorises actions in your system. The two are different mechanisms
answering different questions, and a system needs both.

## It cannot stop code that legitimately read a value

Once a `Reveal` hands back plaintext, what the holder does with it is beyond the gate. Writing it
to a log, returning it in a response, or putting it back into a conversation are all ordinary code.

The same is true of the functions a derivation or a question runs: they are handed the plaintext in
order to compute, and a function that also writes it somewhere has leaked it, whatever it returns.

What the design gives you instead is that the list of code holding a `Reveal`, and of functions
that read values, is short, greppable and in the [manifest](guides/reviewing.md).

## The store reads everything, and is not for your code

`Storage.value()` hands over whatever it holds with no ceiling asked and no line written, because
the portal calling it already did both. So a store is bound to a charter and handed to nothing
else: the Spring starter registers it so that no bean can be injected with it by type, and what
operating it needs is `StorageIntegrity`, which reads no value. Code that looks the store up by
name anyway has every value and leaves no trace — a deliberate act, which is where the rules above
stop.

## Questions read a value a bit at a time

A `Query` never hands the value over, which makes it look like the safe way to use one. But each
answer is a bit and the asker chooses the question, so enough questions reconstruct the value.

Nothing counts them, deliberately. A budget small enough to stop reconstruction of a card number is
small enough to make the feature useless — the bound usually cited is about aggregates over many
rows, and this is an exact predicate over one value, so any number of questions above zero leaks.
What actually separates a probe from a question is the shape of what the caller may choose, which
only whoever wrote the predicate knows.

What limits the exposure is the **ceiling**: a value you may not ask about gives you no questions
at all. What makes it visible is the **trail**, which records every question against the value it
was asked about. The operation, the value, the outcome, the reason and the time are stored in the
clear, so *"how many questions were asked about this value this hour"* is plain SQL you can run and
alert on.

*Who* asked is not. The access context is whatever the application put in it — a tenant, an
email address, a token — so it is encrypted like a label, and counting per caller means decrypting
it: `storage.trail()` reads lines back decrypted and checked, for an investigation. For alerting in
real time, keep a per-caller count where the caller is known, at the edge.

If the asker is a loop rather than a person, put the limit where the loop is.

## Tail truncation is undetectable from the data alone

Removing the most recent lines of the audit chain leaves a chain that verifies. Detecting it
requires an anchor kept somewhere the writer cannot reach.

## A refusal reveals existence

The ceiling is checked before the type, so a refusal will not say what kind of value it turned
away. It does still distinguish an identifier that exists from one that does not. Identifiers carry
74 random bits, so this answers only about identifiers a caller already holds.

## An identifier reveals when its value was made

Identifiers are time-ordered UUIDs (v7), so the index appends rather than scatters, and each one
discloses its value's creation time to the millisecond. An occluded reference is the thing designed
to travel — into logs, other services, a model — so that is a real disclosure. A store that would
rather leak nothing overrides `Storage.freshId()` and pays for the scattered index.

## It never joins your transaction, on purpose

Every act the store performs is its own transaction, on its own connection, committed before the
operation returns. An `occlude` inside an `@Transactional` method **does not roll back with it** —
and neither does anything else the store records.

That is the point. The record is evidence of what happened, and what happened does not roll back:
a `@Transactional` method that reveals a card number, sends it to the processor and then throws has
still disclosed the card, and a run of refused attempts that ends in an exception was still a run of
refused attempts. Joined to the caller's transaction, both lines would vanish with it — and anyone
who could make a transaction fail could erase their own trail. It cannot be split either, keeping
values in the caller's transaction and lines out of it: the record is one chain behind one lock,
and an independent append would wait on the lock its own transaction holds.

What this costs is an orphan now and then: a value occluded in a transaction that rolled back, which
nothing in the application refers to. It is encrypted, harmless, and erasable. If the application
must never keep one, record the reference in the same place as the rest of its work and erase what
a rolled-back transaction left behind.

The store is never handed the caller's transaction by accident either. The Spring starter builds it
on what a transaction-aware `DataSource` proxy wraps, never on the proxy; and a connection that
arrives in the middle of a transaction that has already written is refused, loudly, rather than
committed early.

## Every operation costs round trips, and the record is serial

Each operation is its own transaction: two or three statements plus an append to the audit chain.
Appends are ordered, because each line signs the one before it, so they queue on one advisory lock
— measured at about **180 appends a second from one writer and 900 from eight** on a local Postgres.
Writers overlap on everything else and wait only for that. Size for it before putting a reveal in
a hot loop.

## Erasing a customer needs your own index

`Erasure.erase` takes one value and removes everything derived from it. Nothing can find a
customer's values by who they belong to: labels are encrypted, and that is the point of them. An
application that must erase everything about someone keeps its own list of the references it
occluded for them.

## One key for everything

Every tenant's values are wrapped under the same current key-encryption key. Separate keys per
tenant — so destroying one tenant's key erases exactly them — would need a `DataKeyProvider` that
chose a key by label, and nothing passes it one yet.

## One backend, and no migrations yet

Postgres only — the storage uses advisory locks, recursive CTEs, `FOR SHARE` and `clock_timestamp()`.
Schema creation is `CREATE TABLE IF NOT EXISTS` and nothing alters an existing table, so a database
written by one pre-release commit is not necessarily readable by the next.

## The privileged methods are public on the implementation

`Charter` is the interface every bean is handed, and it cannot bind. The implementation can, and
`bind` is public — so a cast on an injected bean defeats
the guarantee. The interface is a statement of intent backed by what code asks for, not a sandbox.
