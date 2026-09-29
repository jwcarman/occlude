# Getting Started

A worked example: a support desk that reads customer emails, where the email body must never reach
a vendor model and cardholder data must never reach any model.

## 0. Add the dependencies

Import the BOM once, and every Occlude module agrees on a version:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.jwcarman.occlude</groupId>
      <artifactId>occlude-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Then one of three, depending on where you are:

| You have | Add |
|---|---|
| Spring Boot and Postgres | `org.jwcarman.occlude:occlude-spring-boot-starter`, and the `org.postgresql:postgresql` driver |
| Plain Java and Postgres | `org.jwcarman.occlude:occlude-jdbc`, `org.jwcarman.codec:codec-jackson`, and the Postgres driver |
| Nothing yet, and want to try it | `org.jwcarman.occlude:occlude-core`, which includes `MemoryStorage` |

Occlude does no cryptography or serialisation of its own. It uses
[codec](https://github.com/jwcarman/codec) (`org.jwcarman.codec`), a small library that turns
values into bytes and encrypts, signs and versions them. `occlude-jdbc` brings its encryption in
transitively; `codec-jackson` is how values become bytes, and the Spring starter includes it. You
will meet codec's `DataKeyProvider` when you supply keys — see [Storage](storage.md).

## 1. Say what you ask about values

```java
public final class BillingAxes {
  public enum Integrity { ENDORSED, UNENDORSED }
  public enum Sensitivity { ORDINARY, PERSONAL, CARDHOLDER }

  public static final Axis<String>      TENANT      = Axis.matching("tenant").required();
  public static final Axis<Integrity>   INTEGRITY   = Axis.ladder("integrity", ENDORSED, UNENDORSED);
  public static final Axis<Sensitivity> SENSITIVITY =
      Axis.ladder("sensitivity", ORDINARY, PERSONAL, CARDHOLDER);
}
```

Note which end of `INTEGRITY` is which. Untrusted data is the **more** constrained end, because it
is the dangerous thing to handle — so vouching for something moves it *down*, which is why the
operation is called lowering.

## 2. Constitute a charter

```java
DefaultCharter charter = new DefaultCharter(Axes.of(TENANT, INTEGRITY, SENSITIVITY));
```

A charter is where authority is declared. It knows nothing yet about where values live or who is
asking; both arrive when it is brought into force.

## 3. Declare the doors

```java
static final OccludedType<Mail> MAIL = OccludedType.of("mail", Mail.class);

Occlude<Mail> customerMail = charter.source("customer-mail", MAIL,
    ctx -> Label.of(TENANT, ctx.get("tenant").orElseThrow())
                .with(INTEGRITY, UNENDORSED)
                .with(SENSITIVITY, PERSONAL));

Reveal<Mail> quarantinedLlm = charter.reveal("quarantined-llm",
    ctx -> Ceiling.of(TENANT, Constraint.any())
                  .with(INTEGRITY, Constraint.any())
                  .with(SENSITIVITY, Constraint.atMost(PERSONAL)),
    MAIL);

Reveal<Mail> vendorLlm = charter.reveal("vendor-llm",
    ctx -> Ceiling.of(TENANT, Constraint.any())
                  .with(INTEGRITY, Constraint.atMost(ENDORSED))
                  .with(SENSITIVITY, Constraint.atMost(ORDINARY)),
    MAIL);
```

**The type's name is permanent.** `"mail"` is written beside every value and checked when one is
read back, so it behaves like a schema version: renaming it orphans what is stored. Say it
yourself, as above, for anything that should outlive a refactor. `OccludedType.of(Mail.class)`
derives one from the class instead — `@OccludedName` if it has one, otherwise the kebab-cased
simple name — which drops any enclosing type, so two nested `Invoice` records collide and are
refused at startup. A container needs a name and a `TypeRef`:
`OccludedType.of("cards", TypeRef.listOf(TypeRef.of(Card.class)))`.

Erasing a value and reading its label are portals too, each declared with what decides it:

```java
Erasure compliance = charter.erasure("compliance", (label, ctx) -> ctx.has("role", "compliance"));
Inspection supportDesk = charter.inspection("support-desk",
    ctx -> Ceiling.of(TENANT, Constraint.atMost(ctx.get("tenant").orElseThrow()))
                  .with(INTEGRITY, Constraint.any())
                  .with(SENSITIVITY, Constraint.any()));
```

Hold onto these. They are the authority, and each carries everything it needs; there is no way to
look them up later.

## 4. Bring it into force

```java
charter.bind(Bindings.of(new MemoryStorage()).withIdentity(() -> AccessContext.of(Map.of(
    "tenant", CurrentTenant.get(),
    "role", currentRole()))));
```

Once, irreversibly. Before this, no portal works; after it, no further authority can be
constituted, and the charter itself is no longer needed.

`withIdentity` is how identity reaches the gate without being threaded through every call. A
`ThreadLocal`, a `ScopedValue`, Spring's holders — occlude has no opinion. It is supplied here,
beside the storage, because it is where identity lives in this environment rather than authority
the application grants. An application with no notion of identity says so with
`withoutIdentity()` — there is no way to bind without deciding.

To watch every operation as a timer and a span, hand binding a Micrometer `ObservationRegistry` —
`Bindings.of(storage).withIdentity(...).observedBy(registry)`. Each observation carries the
operation, the portal, the outcome and a refusal's reason, and never a value, an identifier, a label
or an identity; the [Spring Boot guide](spring-boot.md#observability) lists them. The Spring starter
does this for you.

## 5. Use it

```java
Occluded<Mail> held = customerMail.occlude(incoming);

vendorLlm.reveal(held);        // Denied — PERSONAL is above ORDINARY, and it is UNENDORSED
quarantinedLlm.reveal(held);   // Allowed
```

## 6. Print what you built

```java
System.out.println(charter.manifest(AccessContext.of(Map.of("tenant", "acme"))));
```

See [Reviewing a Charter](reviewing.md) for what to look for — and note the argument. A ceiling
that reads the tenant cannot be rendered without one.

## Next

- Durable, encrypted storage: [Storage](storage.md)
- Wiring it in an application: [Spring Boot](spring-boot.md)
- The boundaries: [What Occlude Does Not Do](../limits.md)
