# Spring Boot

Add the starter and declare your axes. That is the whole of the wiring.

```xml
<dependency>
  <groupId>org.jwcarman.occlude</groupId>
  <artifactId>occlude-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@Bean
Axes billingAxes() {
  return Axes.of(TENANT, INTEGRITY, SENSITIVITY);
}
```

That bean is the trigger: an application that never says what it asks about values gets no charter
rather than a guessed one.

## Who constructs what

The starter constructs the charter and is therefore **the only thing able to bind it**. The
application receives a `Charter` to declare on — never one it could bring into force.

`Charter` declares portals and reports on the *declarations*: `axes()` and `manifest()`. Erasing a
value takes an `Erasure` and reading its label takes an `Inspection`, declared like any other
portal and handed to whatever needs them.

!!! note "It is a statement of intent, not a sandbox"
    The implementation's privileged methods are public, so a cast on the injected bean defeats
    this. What the interface buys is that a class asking for the ability to bring authority into
    force has to say so in a way a reviewer can see.

## Declaring portals

Declare them in a `@Bean` method and hand them to whatever needs them:

```java
@Bean
DisputeService disputeService(Charter charter, Invoices invoices) {
  Occlude<Mail> customerMail = charter.source("customer-mail", MAIL,
      ctx -> label(ctx, UNENDORSED, PERSONAL));

  Reveal<Invoice> paymentProcessor = charter
      .sink("payment-processor", ctx -> ceiling(ctx, ENDORSED, CARDHOLDER), INVOICE)
      .reading(INVOICE);

  return new DisputeService(customerMail, paymentProcessor);
}
```

Nothing here knows when the charter is bound, and nothing has to: every portal comes into force when
it is, and none of these is used before the context is ready.

## Where identity comes from

```java
@Bean
AccessContextProvider currentAccess() {
  return () -> AccessContext.of(Map.of(
      "tenant", CurrentTenant.get(),
      "role", currentRole()));
}
```

The starter binds the charter with this, beside the storage. The tenant reaches the gate without
being threaded through every call, and nothing a caller passes can influence it. Without one, every
access is nobody in particular.

## Storage

Contribute a `Storage` bean, or put `occlude-jdbc` on the classpath and give it a `DataSource`, keys
and a root. See [Storage](storage.md).

```yaml
occlude:
  keys:
    current: k2
    keks:
      k1: ${OCCLUDE_KEK_1}   # base64 AES-256 key-encryption keys
      k2: ${OCCLUDE_KEK_2}
  roots:
    current: r1
    secrets:
      r1: ${OCCLUDE_ROOT_1}  # base64
    mac: HMAC_SHA256         # or HMAC_SHA384, HMAC_SHA512
```

Keys and roots come from the environment or a secret store, never a committed file. A
`DataKeyProvider` bean — a KMS, Vault — replaces the configured keys entirely. Without keys, or
without a root, startup fails naming what is missing.

If an application declares a charter and nothing supplies storage, startup fails with a message
saying so. It used to carry on silently and every portal refused at request time instead.

## The charter endpoint

With Actuator on the classpath, the charter becomes explorable at runtime. It is off until you
expose it, like every other endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: charter
```

```
/actuator/charter                      everything, and what to ask next
/actuator/charter/types                every occluded type this charter mentions
/actuator/charter/types/{name}         everything declared about values of that type
/actuator/charter/sources/{name}       one door in
/actuator/charter/sinks/{name}  one door out
/actuator/charter/derivations/{name}   one way of making a value from another
/actuator/charter/findings             what is provably unreachable
```

The drill-down by type is the one to reach for. *"What can happen to an invoice?"* is one request:

```json
{
  "type": "invoice",
  "occludedBy": [],
  "revealedAt": [ { "name": "support-ui",         "reads": ["invoice"] },
                  { "name": "payment-processor",  "reads": ["invoice"] } ],
  "madeBy":     [ { "name": "mail.confirmedInvoice", "detail": "mail -> invoice",
                    "weakens": true } ],
  "readBy":     [ { "name": "invoice.card.last4",    "detail": "invoice -> last4",
                    "weakens": true } ],
  "findings": []
}
```

An invoice is never occluded directly — it can only be *made*, by one operation that weakens a
label.

### Findings are proofs

`findings` reports what is provable from the declarations alone: a source whose values can never be
revealed anywhere, a door reading a type nothing can produce, a derivation whose output nothing
reads. These are facts about a graph, not heuristics — and the middle one is usually a rename that
went half-applied, which without this surfaces as a refusal at request time rather than at startup.

It deliberately proves nothing about *labels*. A source's label and a sink's ceiling are
both functions of the access, so "can an unendorsed value reach the vendor model" has no general
answer — only one per caller. Render a manifest for that caller and read the ceilings.

### What it does not report

No label, no lineage, no identifier of anything held. And no evaluated ceilings: the access at an
operations endpoint belongs to whoever is looking at it, not to the caller a door was declared for.

It is still a map of your security posture. Protect it as you would `/actuator/beans`.

## Logging the manifest at startup

```yaml
occlude:
  log-manifest: true
```

Prints the manifest as the charter is bound. Pair it with a build that renders one per
representative access and diffs it — see [Reviewing a Charter](reviewing.md).
