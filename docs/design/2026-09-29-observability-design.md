# Observability

Status: landed.

## What prompted this

The audit trail is evidence: one tamper-evident line per access, per value, kept in the
application's own database and read by auditors. It answers "who did what to which value" after the
fact. It is not built to answer "is something wrong right now", and nobody pages on-call from a
table.

The events that matter operationally are already known to the library, and today they go nowhere
but that table:

- A spike in `ABOVE_CEILING` refusals at one sink. That is either somebody probing or a deploy that
  broke a ceiling, and either way somebody should know within minutes.
- Any `NOT_AS_SIGNED` or `UNREADABLE` refusal. Each one means the tables were edited or a key went
  missing, and each one deserves a page.
- A key service outage. Since the third review this is an `IllegalStateException`, deliberately
  kept out of the trail, so today it is visible only as a stack trace.
- Latency. Every reveal now decrypts and verifies a commitment, and a slow request should show how
  much of it was spent in Occlude.
- Integrity. `sweep()`, `firstBrokenEntry()` and `brokenValues()` exist, but only if somebody
  remembers to call them.

Libraries of this kind are expected to be observable. Spring Security, for example, emits a
Micrometer observation for every authentication and authorization decision. The question is how to
do it without the telemetry becoming a way around the protection.

## The rule: telemetry describes the system, never the data

Everything the audit trail protects stays out of telemetry: plaintext, labels, the `detail` behind
a reason, context attributes, and value ids. A tenant name is exactly what a label hides, and a
tracing backend is not encrypted, signed or access-controlled like the trail is.

What remains are the dimensions the application chose itself when it declared the charter, which
describe the system rather than any value in it. They are the same ones the manifest already shows:

| Key | Values | Cardinality |
|---|---|---|
| `occlude.operation` | `conceal`, `reveal`, `derive`, `query`, `erase`, `inspect` | 6 |
| `occlude.portal` | the declared name (`note-desk`, `shout`) | as many as were declared |
| `occlude.outcome` | `allowed`, `refused`, `failed` | 3 |
| `occlude.reason` | the coarse code (`ABOVE_CEILING`, `NO_SUCH_VALUE`, `NOT_AS_SIGNED`, `UNREADABLE`...) or `none` | enumerated |
| `error.type` | the fully-qualified class name of anything thrown, or `none` | small |

The observation itself is named `occlude.operation`. OpenTelemetry's semantic conventions have
nothing for authorization decisions, so these follow the general rules: a library's own attributes
are namespaced with its name, and `error.type` is the convention's attribute for the class of error
an operation ended with. The `user.*` and `enduser.*` attributes exist and are deliberately never
set.

Every value is either an enum or a name fixed at startup, so cardinality is bounded by the charter
itself.

**Exceptions are recorded by type, never by message.** This is the easy one to get wrong: Micrometer
and OpenTelemetry both attach a thrown exception's message and stack trace to the span by default,
and `StorageIntegrityException` says "the payload stored for occ_7f3a… is not what was signed". So
the hook never receives the exception at all, only its class.

## Decisions

### Micrometer's Observation API, directly, in core

Micrometer is already the abstraction. Its Observation API is the de facto instrumentation facade
on the JVM, bridged to OpenTelemetry and Brave for traces and to every metrics backend for timers,
and it is how Spring Framework and Reactor instrument themselves. A second, Occlude-shaped
interface in front of it would be one more thing to learn and adapt, for no adopter's benefit.

So `occlude-core` depends on `micrometer-observation`: two small jars (it needs only
`micrometer-commons`) and nothing transitive. The default is `ObservationRegistry.NOOP`, so an
application that never configures a registry records nothing and pays for nothing.

`Trail.reading` and each operation run inside one observation, around the whole operation: gate,
storage read, decryption, derivation, audit write. That makes it a timer and a span at once, and a
trace shows the JDBC calls nested inside the Occlude span that caused them.

### It is supplied at binding, like storage and identity

Binding is where a charter's runtime comes into being, so the registry arrives there too:

```java
charter.bind(Bindings.of(storage).withIdentity(currentAccess).observedBy(registry));
charter.bind(Bindings.of(storage).withIdentity(currentAccess));   // ObservationRegistry.NOOP
```

`Bindings` exists for exactly this: another thing arriving at binding is one more optional method,
not another parameter on `bind`. Unlike identity, which `Bindings` makes you decide, leaving the
registry out is harmless. No identity quietly widens a ceiling written as "unless the context says
otherwise"; no registry means only that nobody is watching.

### Measured where the outcome is known, and only with what describes the system

Each operation (`Occluding`, `Revealing`, `Deriving`, `Querying`, `Erasing`, `Inspecting`) already
ends by building a result with an outcome and a reason. The observation wraps each entry point and
ends with the result it returns:

- a granted result: `occlude.outcome=allowed`
- a refused result: `occlude.outcome=refused`, `occlude.reason=<the reason's name>`
- an exception: `occlude.outcome=failed`, `error.type=<the exception's class name>`, then rethrown

Occlude owns what goes on the observation, and that is where the rule above is enforced:

- **Key values come from an `OccludeObservationConvention`** that sets only the keys in the table
  above. Applications may replace it the way Micrometer expects, and the default is the safe one.
- **`observation.error(e)` is never given the real exception.** Micrometer's handlers and the
  tracing bridges put its message on the span, where a storage exception's message names a value
  id. When an exception ends an operation, the observation is given an `OccludeFailure` whose
  message is only the real class name, with no cause and no stack trace, so the span is still
  marked as an error. A refusal returned as a result is not an error and marks nothing, as a 4xx
  does not mark a server's span.
- **An `ObservationDocumentation` enum lists every key**, so the conventions are discoverable, and
  a test can assert nothing else ever appears.

`NOT_AS_SIGNED` and `UNREADABLE` surface as exceptions from `Trail.reading` rather than refused
results; they are `refused` with their reason, and `error.type` names the exception.

Measuring is not auditing. The trail stays the one record of what happened, written in the same
transaction as what it describes. An observation is best-effort: a handler that throws never
changes the outcome of the operation it was watching.

### The Spring starter uses Spring Boot's registry

The auto-configuration depends on Spring Boot's `spring-boot-micrometer-observation` module and
runs after its `ObservationAutoConfiguration`, which defines the registry `@ConditionalOnMissingBean`.
So there is always a registry, and the binder injects it directly, the way Boot's own observation
auto-configurations do; an application's own registry replaces Boot's. It records nothing until a
handler is added. With Actuator, Spring Boot's `DefaultMeterObservationHandler` turns each
observation into a timer (`occlude.operation`, with count, latency and an active gauge), and a
tracing bridge, if there is one, into a span. Nothing about this needs an Occlude-specific handler
or adapter.

### Integrity is checked on a schedule, when asked for

`StorageIntegrity.check()` runs every verifier at once -- the chain, the values' digests, missing
values, every ciphertext against its commitment -- and returns an `IntegrityReport` with the head
to anchor. It belongs to `occlude-jdbc`, not the `Storage` SPI, so scheduling it lives in the JDBC
auto-configuration. It is off by default, because a check reads and decrypts every row, and how
often that is affordable depends on the store:

```yaml
occlude:
  integrity:
    interval: 1h        # absent: never runs
```

Each run:

- is an observation, `occlude.integrity`, tagged `occlude.integrity.result` = `intact`,
  `unreadable`, `altered` or `failed`. A timer per result is enough to alert on (`altered`
  increasing) and needs no
  dependency beyond the one core already has; gauges would have needed `micrometer-core`.
- logs at ERROR when anything is altered or the trail is broken, and WARN for unreadable values,
  with counts only, never ids (the ids are in the sweep's result for whoever investigates)
- logs the current `TrailHead`, so anchoring can be as simple as shipping that log line somewhere
  the database's writers cannot reach

A run that cannot finish, such as during a key service outage, is `failed` and concludes nothing:
it neither claims the store was checked and clean nor that it was altered.

### No health indicator by default

A Spring Boot `HealthIndicator` reporting DOWN on tampering is tempting. It is also dangerous:
health endpoints drive liveness and readiness probes, and an orchestrator that restarts every
instance because somebody edited one row turns an integrity finding into an outage. Tampering is a
page, not a restart, and the `altered` timer is what pages.

## What does not change

- The audit trail is still the only record, and still cannot be turned off.
- Nothing is logged per operation. The trail is the log, and a second, unprotected copy of it is
  what this design exists to avoid.
- Core gains one dependency, `micrometer-observation`, and records nothing unless given a
  registry.

## Decided

1. **Storage findings are `refused`**, with reason `NOT_AS_SIGNED` or `UNREADABLE` and `error` set
   to the storage exception's class. One alert rule covers tampering, and it matches the trail.
2. **No health indicator**, for the reason above.
3. **Portal names stay.** They are chosen by the application and already public in the manifest.
   One that names a sink after a customer can drop the key with a convention.
4. **No OpenTelemetry module.** Micrometer's bridge covers it, in plain Java as in Spring.
5. **Landed with 0.1**, rather than waiting.

## Testing

- Core: Micrometer's `TestObservationRegistry` asserts one observation per operation, with the
  right outcome and reason, for every portal kind, including exceptions and storage findings. A
  handler that throws changes no outcome.
- A test that runs every operation and every refusal, then asserts no key value and no recorded
  error on any observation contains a value id, a label or a context attribute. The rule above as
  a test, so a later change cannot break it quietly.
- Spring: `ApplicationContextRunner` with and without an `ObservationRegistry` bean, asserting the
  observation's name and keys when there is one and nothing recorded when there is not.
- JDBC: the scheduled sweep publishes the gauges, logs counts without ids, and keeps its last
  values when a run fails.
