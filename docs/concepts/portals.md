# Portals

A **charter** is where an application constitutes its authority. Each declaration hands back the
one object able to perform that operation — a **portal**.

| portal | what holding it lets you do |
|---|---|
| `Occlude<T>` | hand over a real value, leave with an occluded reference |
| `Reveal<T>` | turn an occluded reference back into a value, at one declared sink |
| `Derivation<I,O>` | make one value from another |
| `Fold<I,O>` | make one value from many |
| `Query<I,Q>` | ask one question of a value without the value leaving |
| `Erasure` | forget a value and everything derived from it — see [Erasing](erasing.md) |
| `Inspection` | read a value's label and lineage, without the value |

## Authority is held, never looked up

There is deliberately no method that trades a name for the door it names. A name is what the
manifest and the audit trail call a door; it is not a way through it.

Miller's four ways to come by a capability are initial conditions, parenthood, endowment and
introduction. **Lookup by name is not among them**, and adding it would quietly return this library
to policing labels rather than distributing authority.

The practical consequence: *"which code in this application can read cardholder data?"* is answered
by grepping for a constructor parameter.

```java
public final class ChargeCard {
  private final Reveal<String> toGateway;      // this class can. Nothing else can.

  public ChargeCard(Reveal<String> toGateway) {
    this.toGateway = toGateway;
  }
}
```

## Occlude takes no label

```java
Occlude<Mail> customerMail =
    charter.source("customer-mail", MAIL, ctx -> label(ctx, UNENDORSED, PERSONAL));

Occluded<Mail> held = customerMail.occlude(incoming);   // no label argument
```

The door carries its own label, decided once when it was declared, so code holding it writes at
that label and no other. A service handed the door for customer-submitted disputes **cannot create
cardholder data** — not "is refused at runtime", but cannot express the operation, because the only
door it has says something else. Writing at another tenant's label is less refused than unsayable.

The label may still depend on who is acting: a door fixes what is a property of the door itself —
what arrives there, how far it is trusted, how sensitive it is — and reads the tenant from ambient
context. So it is not quite a constant, but nothing a caller passes influences it. A source takes its
label in one of three shapes:

```java
charter.source("notes", NOTE, Label.of(TENANT, "acme"));                  // always the same
charter.source("customer-mail", MAIL, ctx -> label(ctx, UNENDORSED, PERSONAL)); // who is acting
charter.source("claims", CLAIM, (claim, ctx) -> labelFor(claim, ctx));    // and what arrived
```

The last reads the arriving value itself — a claim that mentions a card is labelled `CARDHOLDER`,
say. It is the door's function doing it, declared once and listed in the manifest, not the caller.
Occluding is refused with a `RefusedException` when the function cannot say (`SOURCE_CANNOT_LABEL`)
or leaves a required axis unsaid (`INCOMPLETE_LABEL`).

!!! note "Occluding is the axiom"
    Occluding is where data enters, and at that moment there is no earlier label to check against.
    Monotone join makes it a *theorem* that derivation cannot weaken a label; occluding is the
    axiom that theorem rests on. Declaring doors makes those axioms enumerable, which is all
    anything can do.

## There is no reveal without a sink

```java
Reveal<Invoice> paymentProcessor =
    charter.reveal("payment-processor", ctx -> ceiling(ctx, ENDORSED, CARDHOLDER), INVOICE);

// a door that reads several types is declared once, and read through per type:
Sink vendor = charter.sink("vendor", ctx -> ceiling(ctx, ENDORSED, ORDINARY), MAIL, NOTE);
Reveal<Mail> vendorMail = vendor.reading(MAIL);

Revealed<Invoice> out = paymentProcessor.reveal(held);
```

No overload omits the sink. You cannot obtain plaintext "in general", only plaintext for
somewhere — and that somewhere is what the ceiling hangs off and what the audit records.

`Revealed<T>` is a sealed result rather than an exception: being turned away while reading is an
ordinary outcome worth branching on.

```java
switch (out) {
  case Revealed.Allowed<Invoice> allowed -> send(allowed.plaintext());
  case Revealed.Denied<Invoice> denied   -> log(denied.reason());
}
```

## Asking a question

A question looks at the value and returns one bit, and the value never leaves:

```java
Query<Mail, String> mentions = charter.query("mail.mentions", MAIL, String.class,
    (mail, text, ctx) -> mail.body().contains(text),
    q -> q.accepting(ctx -> ceiling(ctx, UNENDORSED, PERSONAL))
          .availableTo(ctx -> ctx.has("role", "agent")));

Answer answer = mentions.ask(held, "INV-4471");
if (answer.isTrue()) { ... }         // ran, and said yes
if (answer.isFalse()) { ... }        // ran, and said no -- a refusal is neither
```

`accepting` says which values it may look at; `availableTo`, optional, says in which contexts it is
offered at all. Ask it against plain values only: a question handed a `Consumer` or a list could put
the value there, and the manifest lists any that are not, as a `not-a-plain-value` finding. Enough
questions read a value a bit at a time — see [What Occlude Does Not Do](../limits.md).

## Reading a label

An `Inspection` reads what the record says about a value — its label and its lineage — without the
value. It is a portal of its own because a label can say as much as the value it describes:

```java
Inspection supportDesk = charter.inspection("support-desk",
    ctx -> Ceiling.of(TENANT, Constraint.atMost(ctx.get("tenant").orElseThrow())));

supportDesk.inspect(held).value()
    .ifPresent(seen -> render(seen.label(), seen.lineage()));
```

## What comes back

Every portal but `Occlude` returns a sealed result rather than throwing, because a refusal is an
ordinary outcome: a prompt renders the handle instead, a tool reports that it cannot proceed. Each
has `value()` — what it produced, when it produced anything — `succeeded()`, and `orThrow()` for code
that cannot go on without it, which throws a `RefusedException` naming the reason.

| portal | result | when it succeeds | reasons it refuses |
|---|---|---|---|
| `Reveal` | `Revealed` | `Allowed(plaintext)` | `NO_SUCH_VALUE`, `WRONG_TYPE`, `ABOVE_CEILING` |
| `Derivation`, `Fold` | `Derived` | `Made(occluded)` | `NO_PARENTS`, `NO_SUCH_VALUE`, `WRONG_TYPE`, `ABOVE_CEILING`, `NOT_AVAILABLE_HERE`, `NOT_A_LOWERING`, `DECLINED`, `FAILED` |
| `Query` | `Answer` | `Answered(value)` — see `isTrue()` / `isFalse()` | `NO_SUCH_VALUE`, `WRONG_TYPE`, `ABOVE_CEILING`, `NOT_AVAILABLE_HERE`, `FAILED` |
| `Erasure` | `Erased` | `Removed(count)` | `NO_SUCH_VALUE`, `NOT_PERMITTED` |
| `Inspection` | `Inspected` | `Seen(label, lineage)` | `NO_SUCH_VALUE`, `ABOVE_CEILING` |

The reason is a code that names a rule and never a value; each refusal also carries a `detail` for
whoever handles it. When application code a portal runs — a ceiling, a derivation — throws, the
refusal says which exception, by class name, and never its message. When that code is a derivation's
or a query's own function, already handed the plaintext, the reason is `FAILED`: a fault, so a caller
can tell it from a `DECLINED` or a `NOT_AVAILABLE_HERE` without reading the record.

## Occluded references disclose nothing

An `Occluded<T>` prints as its identifier and nothing else, and carries no runtime type. Knowing
that an occluded reference is a card token rather than a display name is itself a disclosure, so a refusal
will not tell you either — the ceiling is checked before the type, and a caller who may not see the
value is not told what kind of value it is. (Its identifier does disclose when the value was made —
see [What Occlude Does Not Do](../limits.md).)

## The lifecycle

A charter is declared, then **bound** to storage and to where identity comes from, once and
irreversibly. Before binding, no portal will work; after it, no further authority can be
constituted — and the charter has no further part to play, because every portal carries everything
its operation needs.

Whoever constructs a charter keeps the ability to bind it, and that reference is not on the
`Charter` interface every bean is handed. Erasing a value and reading its label are portals like
the rest — `Erasure` and `Inspection` — so the interface declares portals and reports on the
*declarations*, `axes()` and `manifest()`, which describe the system and never a value.
