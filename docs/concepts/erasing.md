# Erasing

"Erase this customer" is a reachability question, which is why lineage is kept.

```java
Erased erased = compliance.erase(occluded);   // compliance is an Erasure you were handed
```

Erasing a value takes everything ever derived from it, however deeply. Descendants go regardless of
their own labels, which is what erasure means: a value derived from two customers dies with either
of them.

## Who may erase

A label has nothing to say about whether a value may be *destroyed* — "possession is not authority"
is a rule about reading. So the authority to erase is a portal of its own, declared with the policy
that decides it:

```java
Erasure compliance = charter.erasure("compliance", (label, ctx) ->
    ctx.has("role", "compliance")
        && ctx.get("tenant")
              .map(t -> Ceiling.of(TENANT, Constraint.atMost(t)).with(SENSITIVITY, Constraint.any())
                               .permits(label))
              .orElse(false));
```

The policy sees **the label of the value being destroyed** as well as who is asking, because who
alone is not enough: a rule checking only the caller's role lets one tenant's compliance officer
destroy another tenant's records.

An application that declares no erasure keeps a store nothing can erase from. One that declares
several — a compliance officer, a retention job — gets each under its own name, and a refusal in
the record says whose policy said no.

`erase` returns `Erased`: `Removed` with how many values went, or `Refused` with a reason
(`NO_SUCH_VALUE`, `NOT_PERMITTED`). Both outcomes are in the record either way. The answer to
*"which code can destroy customer data?"* is **whoever was handed an `Erasure`**, and the manifest
names every one.

## What survives

The audit outlives what it describes. `occlude_audit` has no foreign key into `occlude_value` and nothing
cascades into it: the record that you erased somebody has to survive erasing them, or the system
cannot prove it did the thing it was legally required to do.

Erasure writes one line per value destroyed, in the same transaction as the deletes. Not one line
per call — because erasure is the only operation that makes a value stop existing, so the trail
becomes the only thing that can say the value ever did. A single line saying *"41 values removed"*
cannot tell a lawful erasure from a quiet deletion, since nothing afterwards knows which 41.

## Reachability is computed from what is signed

The set of values an erasure destroys is walked over the lineage that the value digests cover, so
nothing decides what gets destroyed that a signature does not protect.

Deriving takes a shared lock and erasing an exclusive one, so a derivation cannot commit a child of
values an erasure is in the middle of removing. Derivations never block one another; an erasure
waits for those in flight. Erasing is rare and must be complete.
