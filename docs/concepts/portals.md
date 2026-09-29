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
context. So it is not quite a constant, but nothing a caller passes influences it.

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
  case Revealed.Allowed<Invoice> allowed -> send(allowed.value());
  case Revealed.Denied<Invoice> denied   -> log(denied.reason());
}
```

## Occluded references disclose nothing

An `Occluded<T>` prints as its identifier and nothing else, and carries no runtime type. Knowing
that an occluded reference is a card token rather than a display name is itself a disclosure, so a refusal
will not tell you either — the ceiling is checked before the type, and a caller who may not see the
value is not told what kind of value it is.

## The lifecycle

A charter is declared, then **bound** to storage and to where identity comes from, once and
irreversibly. Before binding, no portal will work; after it, no further authority can be
constituted — and the charter has no further part to play, because every portal carries everything
its operation needs.

Whoever constructs a charter keeps the ability to bind it, and that reference is not on the
`Charter` interface every bean is handed. Erasing a value and reading its label are portals like
the rest — `Erasure` and `Inspection` — so the interface declares portals and reports on the
*declarations*, `axes()` and `manifest()`, which describe the system and never a value.
