# Portals activate on binding

Status: landed.

## What prompted this

SonarCloud still reports S6539 "Monster Class" against `DefaultCharter`: 26
type dependencies against a limit of 20. The gate decomposition removed
`Engine` and predicted this, because `DefaultCharter` was two things at once —
the object an application declares its authority on, and the object that
answered for that authority at runtime (`seal`, `label`, `lineage`, `erase`,
and the frozen `Configuration` everything read from).

Splitting that class further would move the count around. The better question
was whether the runtime half belongs on a charter at all, and it does not.

## The model

A portal is handed out when it is declared, and holding it is the only way to
perform its operation. That was already the rule for callers. It was not quite
true inside:

- A `Reveal` carried only its sink's **name**. `Revealing` looked the sink up
  in a `Map<String, SinkSpec>` built from every declaration, and
  `Revealed.Reason.NO_SUCH_SINK` existed for the name that was not there.
  `Answer.Reason.NO_SUCH_QUESTION` was the same shape and was never reachable.
- Every portal reached its operation through the charter's `Lifecycle`, which
  held a snapshot of every declaration so the shared operations could be built
  from it.

After this change a portal carries everything its operation needs, and binding
storage is what switches every portal on at once. Nothing is looked up by
name, and once bound the charter is not needed: the portals keep the runtime
alive, and the charter can be dropped.

## Declaring

```java
DefaultCharter charter = new DefaultCharter(axes);

Occlude<Card> cards = charter.source("cards", CARD, label);
Sink processor = charter.sink("processor", ceiling, CARD);
Reveal<Card> toProcessor = processor.reading(CARD);
Derivation<Card, Last4> last4 = charter.derivation("last4", CARD, LAST4, fn, d -> d.accepting(...));
Query<Card, String> matches = charter.query("matches", CARD, String.class, asking, q -> q.accepting(...));

Erasure compliance = charter.erasure("compliance", (label, ctx) -> ctx.has("role", "compliance"));
Inspection support = charter.inspection("support-desk", ceiling);

charter.bind(storage, currentAccess);
```

### Two new portals

**`Erasure`** replaces `mayErase(...)` and `DefaultCharter.erase(root)`. The
`Charter` Javadoc already said that erasure would "earn a portal like every
other operation" the day something needed it. The policy moves from the
charter onto the portal, so an application may declare more than one — a
compliance officer and a retention job — each with its own policy and its own
name. `erase(root)` returns `Erased`:

```java
sealed interface Erased {
  record Removed(int count) implements Erased {}
  record Refused(Reason reason, String detail) implements Erased {}
  enum Reason { NO_SUCH_VALUE, NOT_PERMITTED }
}
```

The rule does not change: the policy is asked about the root, and descendants
go with it.

**`Inspection`** replaces `DefaultCharter.label(...)` and `lineage(...)`, which
answered with no ceiling and no line in the record. A label names a tenant or a
project codeword — storage encrypts it and `Gate.because` keeps it out of
refusals so a caller cannot learn a classification by probing — so an ungated
read contradicted two deliberate decisions. `inspect(occluded)` is checked
against the inspection's ceiling, recorded as `INSPECT`, and returns the label
and the lineage together, one check and one line per look:

```java
sealed interface Inspected {
  record Seen(Label label, Lineage lineage) implements Inspected {}
  record Refused(Reason reason, String detail) implements Inspected {}
  enum Reason { NO_SUCH_VALUE, ABOVE_CEILING }
}
```

Both are result types like `Revealed`, `Answer` and `Derived`, with an
`orThrow()` for callers who want the exception.

### What leaves

- `Charter.sealed()`, `Charter.currentAccess(...)`, `Charter.mayErase(...)`.
- `Charter.sink(SinkSpec)`, which registered a sink and returned no portal: the
  only way to use one was by name. `SinkSpec` and `Sinks` become
  package-private, since nothing public takes one any more.
- `DefaultCharter.seal`, `sealed`, `label`, `lineage`, `erase`.
- `Revealed.Reason.NO_SUCH_SINK` and `Answer.Reason.NO_SUCH_QUESTION`, which a
  portal holding its target cannot produce.

### Binding

`DefaultCharter.bind(Storage, AccessContextProvider)`, and not on `Charter`,
for the reason `seal` never was: whoever holds the thing that brings a charter
into force decides when its authority stops growing.

The access provider moves from declaring to binding. It is not authority; it
is where identity comes from in this environment — a test, a batch job, a web
request — exactly like storage. The Spring auto-configuration already said so
in a comment. There is no one-argument overload: an application that forgets
its provider should say `AccessContextProvider.none()` where it binds rather
than silently run as nobody and find out from refusals at request time.

### The rules

| situation | result |
|---|---|
| a portal used before binding | `IllegalStateException`, as before |
| binding twice | `IllegalStateException` |
| declaring after binding | `IllegalStateException`, as before |
| two of one kind under one name | `IllegalStateException` at declaration, now including erasures and inspections |
| two types sharing a stored name | `IllegalStateException` at declaration, as before |

`manifest()` stays on `Charter` and works before and after binding: it
describes the declarations, never a value. It gains two sections, erasures and
inspections, which can show names and — for an inspection — what it accepts
for the rendered access. An erasure's policy is a function of the label and
the access and has nothing further to render.

## At runtime

**`Operations`** is created with the charter, empty, and handed to every portal
as it is minted. `bind` builds the gate, the trail and the six operations —
occluding, revealing, querying, deriving, erasing and the new inspecting — from
the storage and the access provider alone, and publishes them with one
compare-and-set. Each accessor refuses before that. It stops being a record
because it now has a moment at which it changes, and that moment is the only
place anything crosses to the threads that use a portal. `Lifecycle` and
`Configuration` are gone.

**No operation receives a list of declarations.** `Revealing` takes the sink
from the portal, `Erasing` takes the policy from the portal, `Deriving` no
longer checks for duplicate names (declaring does), and the gate is told who is
asking by `bind`.

**Portals are named package-private classes** — `SourcePortal`, `SinkPortal`,
`RevealPortal`, `DerivationPortal`, `FoldPortal`, `QueryPortal`,
`ErasurePortal`, `InspectionPortal` — each holding its spec and `Operations`
and naming itself in `toString`. Classes, not records: a portal is an identity,
and a record would give it value equality and public accessors for its policy.
`Portals` stays as the factory so `DefaultCharter` depends on it rather than on
eight classes.

**`DefaultCharter`**, while declaring, holds the axes, `Operations`, the
type-name registry, the duplicate checks, and an ordered record of what was
declared, which exists only for the manifest.

## What does not change

Storage, the audit trail's shape, and every check order inside an operation. A
reveal, a query, a derivation and an erasure decide exactly as before; only
where their inputs come from moves. Two audit lines change on purpose: a
refused erasure now names the erasure that refused it (it named nothing
before), and inspecting writes a line where reading a label wrote none.

## Proving it

The existing suite is the harness, migrated rather than rewritten:

- `seal(storage)` becomes `bind(storage, ...)`, with the access provider that
  used to be declared.
- A test that erased or read a label through the charter keeps testing the
  gated path through the new portal. A test that only asserted state — whether
  something is held, what it was labelled — reads `Storage` directly, as the
  removal of `holds` did.
- Tests of `sink(SinkSpec)`, `NO_SUCH_SINK` and `NO_SUCH_QUESTION` go with what
  they tested; the ceiling-that-says-nothing path is still reachable through a
  ceiling function returning null.
- New tests cover each rule in the table above and both new portals.

Then Sonar is asked again, for `DefaultCharter` and for `Portals`.

## Sequencing

1. `Operations` becomes the switch; `Revealing` takes its sink from the portal;
   named portal classes. `seal` and `currentAccess` become `bind`.
2. `Erasure` and `Erased`; `mayErase` and `erase` leave.
3. `Inspection` and `Inspected`; `label` and `lineage` leave.
4. `sink(SinkSpec)`, `sealed()` and the unreachable reasons leave; the manifest
   gains its two sections.
5. Spring, the example, and the docs.
