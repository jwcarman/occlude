# Spring Boot

Add the starter and declare your axes. That is the whole of the wiring.

```xml
<dependency>
  <groupId>org.jwcarman.occlude</groupId>
  <artifactId>occlude-spring-boot-starter</artifactId>
  <version>0.1.0</version>
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

An application can construct its own `DefaultCharter` bean instead, and the starter leaves it alone:
whoever constructs a charter binds it. Bind it once everything is declared — a
`SmartInitializingSingleton` is the natural place — because a charter still unbound when the context
has finished refreshing stops startup, rather than leaving every portal to refuse at first use.

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

  Reveal<Invoice> paymentProcessor =
      charter.reveal("payment-processor", ctx -> ceiling(ctx, ENDORSED, CARDHOLDER), INVOICE);

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

Put `occlude-jdbc` on the classpath and give it a `DataSource`, keys and a root, or contribute a
store of your own. See [Storage](storage.md).

The store can hand over any value decrypted, with no ceiling asked and no line written, so no
application bean can have it. It is registered under the name `occludeStorage` and hidden from
injection by type: a bean that asks for a `Storage` in its constructor fails to start. A store of
your own is registered the same way:

```java
@Bean(name = CharterAutoConfiguration.STORAGE, defaultCandidate = false)
Storage storage() {
  return new MemoryStorage();
}
```

What operating the JDBC store needs — checking, sweeping, anchoring, re-encrypting, re-signing — is
published as a `StorageIntegrity` bean, which reads no value; operations code takes that. The trail
read back for an investigation, `AuditTrail`, discloses every label and identity, so it is registered
like the store: ask for it with `@Qualifier(JdbcCharterAutoConfiguration.AUDIT_TRAIL)`. Both are
[Operating a Store](operating.md).

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
  migrate: true              # create the tables at startup; false where you manage the schema
```

Every `occlude.*` property is in [Configuration](../configuration.md).

What properties do not cover is reached with a `JdbcStorageConfigCustomizer` bean, applied before
the store is built — keys per tenant, most often:

```java
@Bean
JdbcStorageConfigCustomizer tenantKeys(TenantKms kms) {
  return config -> config.keyedBy(TENANT, kms::providerFor);
}
```

Keys and roots come from the environment or a secret store, never a committed file. A
`DataKeyProvider` bean — a KMS, Vault — replaces the configured keys entirely. Without keys, or
without a root, startup fails naming what is missing.

If an application declares a charter and nothing supplies storage — or uses `occlude-jdbc` with no
keys — startup fails with Spring's report of the missing bean, rather than serving requests that all
refuse.

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
/actuator/charter                       everything, and what to ask next
/actuator/charter/types                 every occluded type this charter mentions
/actuator/charter/types/{name}          everything declared about values of that type
/actuator/charter/sources[/{name}]      the doors values enter through
/actuator/charter/sinks[/{name}]        the doors values leave through
/actuator/charter/derivations[/{name}]  what makes one value from others
/actuator/charter/questions[/{name}]    what asks one bit of a value
/actuator/charter/erasures[/{name}]     what may forget a value and its descendants
/actuator/charter/inspections[/{name}]  what may read a value's label and lineage
/actuator/charter/findings              what is provably unreachable
```

The drill-down by type is the one to reach for. *"What can happen to an invoice?"* is one request:

```json
{
  "type": "invoice",
  "occludedBy": [],
  "revealedAt": [ { "name": "support-ui", "reads": ["invoice"],
                    "ceiling": "depends on the caller; render a manifest for one to see it" },
                  { "name": "payment-processor", "reads": ["invoice"],
                    "ceiling": "depends on the caller; render a manifest for one to see it" } ],
  "madeBy":       [ { "name": "mail.confirmedInvoice", "detail": "mail -> invoice",
                      "weakens": true } ],
  "readBy":       [ { "name": "invoice.card.last4", "detail": "invoice -> last4",
                      "weakens": true } ],
  "askedAboutBy": [],
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

## Observability

Every operation is a Micrometer observation, `occlude.operation`, through Spring Boot's own
`ObservationRegistry` — the starter brings Boot's observation auto-configuration, so there always is
one. By itself it records nothing. Add Actuator and each operation is a timer; add a tracing bridge
and it is a span too, inside the request that caused it. A registry of your own replaces Boot's, as
usual. Plain Java gets the same with `Bindings.of(storage).withIdentity(...).observedBy(registry)`.

| Key | Values |
|---|---|
| `occlude.operation` | `conceal`, `reveal`, `derive`, `query`, `erase`, `inspect` |
| `occlude.portal` | the declared name |
| `occlude.outcome` | `allowed`, `refused`, `failed` |
| `occlude.reason` | the refusal's code — `ABOVE_CEILING`, `NOT_AS_SIGNED`, `UNREADABLE`... — or `none` |
| `error.type` | the fully-qualified class name of anything thrown, or `none` |

Namespaced as OpenTelemetry's semantic conventions ask of a library's own attributes, with the
conventions' `error.type` for failures.

**Telemetry describes the system, never the data.** No value, identifier, label or identity is on
an observation, and exceptions are recorded by class name only: their messages can name a value, and
tracing backends are not protected the way the trail is. An operation an exception ended still marks
its span as an error, through a stand-in exception that carries nothing but that class name; a
refusal returned as a result marks nothing. Two alerts are worth having from day one: any
`occlude.reason` of `NOT_AS_SIGNED` or `UNREADABLE`, which means the tables were edited or a key went
missing, and a jump in `ABOVE_CEILING` at one portal, which is somebody probing or a deploy that
broke a ceiling. To rename or reshape the observation, register an
`OccludeObservationConvention` on the registry.

The store can also check itself on a schedule, with each run observed the same way — see
[Operating a Store](operating.md#on-a-schedule). To see all of it in Grafana, the example application
runs the whole stack beside its database; its README says how.

## Logging the manifest at startup

On by default: the manifest is printed as the charter is bound. Turn it off with

```yaml
occlude:
  log-manifest: false
```

It is rendered for nobody, since there is no request at startup, so a door whose ceiling reads the
tenant prints `(could not decide for this access (it threw ...))` rather than what it accepts. The
declarations, the weakening list and the findings are all still there, and those are what a log at
startup is for. Pair it with a build that renders one per
representative access and diffs it — see [Reviewing a Charter](reviewing.md).
