# Testing

Three kinds of test, from cheapest to most real: your declarations on their own, your Spring wiring
without a database, and the whole thing against Postgres.

## Your declarations, in plain Java

A charter, a `MemoryStorage` and an identity you control are all it takes. No Spring, no database,
no keys.

```java
class InvoiceChecksTest {

  private final MemoryStorage storage = new MemoryStorage();
  private final Map<String, String> asking = new HashMap<>(Map.of("tenant", "acme"));
  private final DefaultCharter charter = new DefaultCharter(BillingAxes.axes());

  private final Occlude<Invoice> invoices = Invoices.source(charter);
  private final Reveal<Invoice> billingUi = Invoices.billingUi(charter);

  {
    charter.bind(Bindings.of(storage).withIdentity(() -> AccessContext.of(asking)));
  }

  @Test
  void another_tenant_cannot_read_it() {
    Occluded<Invoice> held = invoices.occlude(anInvoice());

    asking.put("tenant", "globex");

    assertThat(billingUi.reveal(held).succeeded()).isFalse();
  }
}
```

Declare your portals the same way production does — a static method or a configuration class that
takes the `Charter` — so the test exercises the real ceilings rather than copies of them.

**Refusals are results, so assert on them directly.** `reveal(...).succeeded()`, `derive(...).value()`,
`ask(...)` returning `Answer.Refused` with a reason. Nothing needs to be caught, and the reason tells
you which rule said no.

**The trail is there to read.** `MemoryStorage.audit()` returns every line, and
`audit(Operation.REVEAL)` one operation's. A test that a refusal was *recorded*, not just returned,
is one line:

```java
assertThat(storage.audit(AuditRecord.Operation.REVEAL))
    .anySatisfy(line -> assertThat(line.outcome()).isEqualTo(AuditRecord.Outcome.REFUSED));
```

**The manifest is a test too.** A charter's findings are provable from its declarations alone, so
assert there are none, or only the ones you have accepted:

```java
assertThat(charter.manifest().findings()).isEmpty();
```

That catches a door reading a type nothing produces — the usual shape of a rename that went
half-applied — before anything runs.

## Your Spring wiring, without a database

The starter will not build a JDBC store without keys and a root, and says so at startup. A test
that is about your beans rather than about storage does not need one: supply a `MemoryStorage`
under the store's name, and the JDBC store backs off entirely.

```java
@TestConfiguration
class InMemoryStorage {

  @Bean(name = CharterAutoConfiguration.STORAGE, defaultCandidate = false)
  Storage storage() {
    return new MemoryStorage();
  }
}
```

For identity, contribute an `AccessContextProvider` a test can steer — a `ThreadLocal` it sets, or
the same request headers production reads. The starter binds with whichever `AccessContextProvider`
bean exists, and warns at startup only when there is none.

## The whole thing, against Postgres

Run a real Postgres with Testcontainers, and give the store keys and a root generated for the run.
Nothing about them needs to be stable across runs, and nothing should be committed:

```java
@Testcontainers
@SpringBootTest
class BillingIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("occlude.keys.current", () -> "test");
    registry.add("occlude.keys.keks.test", () -> random(32));
    registry.add("occlude.roots.current", () -> "test");
    registry.add("occlude.roots.secrets.test", () -> random(32));
  }

  private static String random(int bytes) {
    byte[] secret = new byte[bytes];
    new SecureRandom().nextBytes(secret);
    return Base64.getEncoder().encodeToString(secret);
  }
}
```

The example application does the same with a `demo` profile holding fixed secrets, which is fine
for a demo and exactly what a real application must never check in.

This is also the place to test operations code: take the `StorageIntegrity` bean, and assert that
`sweep().intact()` holds after your test has run its traffic through.
