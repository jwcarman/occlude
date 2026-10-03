# Refusal events

Status: agreed, not started. James decided every question on 2026-10-03.

## What prompted this

The nessy-ap desk uses Occlude 0.1.0 to quarantine vendor mail. In its first live run, every
reading failed. The desk saw nothing, because Occlude wrote each refusal to the trail and told
nobody else. The desk found the failures only when it read the trail.

The `FAILED` reason (landed in `7fe8dd3`) fixes one half of that problem. A caller can now tell a
fault from a decline. The other half is still open: an application has no way to learn of a
refusal when it happens.

Some refusals are a security signal. A vendor email that pushes the agent to reveal a value above
its ceiling is a prompt-injection attempt. An operator wants to know about it in minutes, not at
the next audit.

## What exists today

Every operation is a Micrometer observation. A refusal has the keys `occlude.outcome=refused`,
`occlude.reason`, `occlude.portal` and `occlude.operation`. An application can alert on a metric,
or register an `ObservationHandler<OccludeObservationContext>` to see each refusal.

That is not sufficient for two reasons:

1. **Alerts then depend on telemetry settings.** An application that sets
   `ObservationRegistry.NOOP`, or turns off the `occlude` observations to cut metric volume, also
   loses its alerts. Spring Security keeps the two separate for the same reason: its
   `AuthorizationEventPublisher` does not depend on its observations.
2. **An observation cannot say which value or which caller.** The observability design keeps value
   ids and context attributes out of telemetry, and that rule stays. An alert that says "somebody
   probed `reveal-to-prompt`" without who or what is hard to act on.

## What other systems do

Authorization systems usually keep two channels:

- **A record of every decision.** Examples are Vault's audit devices, OPA's decision logs, AWS
  CloudTrail and the SELinux audit log. In Occlude, this is the trail.
- **An application log that carries faults, not denials.** Spring Security logs a denial at DEBUG
  or TRACE. For alerts, it publishes an `AuthorizationDeniedEvent` that the application can listen
  to.

These details come from memory and can change between versions. They were not verified for this
document.

Occlude has the first channel. This design adds the second, in the form Spring Security uses: an
event.

## The design

### Core: a listener for refusals

Core gets two new public types:

```java
public record RefusalEvent(
    Instant at,
    AuditRecord.Operation operation,
    String portal,
    String valueId,
    String reason,
    AccessContext context) {}

@FunctionalInterface
public interface RefusalListener {
  void refused(RefusalEvent event);

  default RefusalListener async(Executor executor) { ... }
}
```

The application gives the listener to `Bindings`, next to the observation registry:

```java
charter.bind(
    Bindings.of(storage)
        .withIdentity(currentAccess)
        .observedBy(registry)
        .onRefusal(listener));
```

If the application gives no listener, Occlude delivers no events. That is harmless, as a missing
registry is.

### When the event starts

`Trail.audit` writes every line. When the line has the outcome `REFUSED`, the trail calls the
listener after the store accepts the line. The order is:

1. The trail writes the line.
2. The trail calls the listener.
3. The operation returns the refusal to the caller.

This one place covers every refusal, which includes the `NOT_AS_SIGNED` and `UNREADABLE` refusals
that `Trail.reading` writes. An exception that the trail does not record, such as a key service
outage, is not a refusal and does not start an event.

`JdbcStorage` takes its own connection from the `DataSource` and does not join a Spring-managed
transaction. Thus, with the default setup, the event never describes a line that a rollback
removed later. An application that wraps the `DataSource` in a `TransactionAwareDataSourceProxy`
can change that. This was not tested.

### Core calls the listener synchronously

Core starts no threads of its own, and this design keeps that true. The trail calls the listener on
the request thread and catches any `RuntimeException` from it:

```java
try {
  listener.refused(event);
} catch (RuntimeException _) {
  // A listener never decides an outcome, and this outcome is already decided.
}
```

A listener that throws thus has no effect on the outcome or on the trail.

A plain listener runs on the request thread and slows the request if it is slow. Spring Security's
events behave the same way. The docs must recommend `async(...)` for a listener that does slow
work.

### `async(Executor)` moves delivery off the request thread

The default `async` method returns a listener that gives each refusal to the executor:

```java
default RefusalListener async(Executor executor) {
  Objects.requireNonNull(executor, "async on some executor");
  return event -> {
    try {
      executor.execute(() -> refused(event));
    } catch (RejectedExecutionException _) {
      // A closed executor, at shutdown. The trail still has the line.
    }
  };
}
```

- **The parameter is an `Executor`.** An `ExecutorService` is an `Executor`, so a caller can pass
  either one. The decorator needs only `execute`.
- **The caller owns the executor.** The caller closes it, and its bounds are the caller's bounds.
  A thread pool has a size and a queue. A virtual thread for each task has no bound, and that is
  the caller's choice.
- **Occlude does not throttle.** Occlude sets no limit on deliveries in flight, and it does not
  count or log drops. A listener that calls a slow service, such as a chat webhook, must throttle
  itself.
- **A rejected task has no effect.** At shutdown, an executor can throw
  `RejectedExecutionException`. The decorator catches it, so a closed executor cannot change an
  outcome.
- **An exception inside the delivered task stays in the executor.** The executor handles it as it
  handles any task that throws. The outcome was decided before the task started.

### What asynchronous delivery costs

- **Thread-bound context stays on the request thread.** The listener has no caller transaction, no
  `SecurityContextHolder`, no MDC and no current span. For this reason, the event carries the
  access context itself.
- **Delivery is best-effort.** The order of two events is not fixed. An event can be lost at
  shutdown.
- **The trail is the truth.** An event is a notice. The docs must say this in plain words.

### What the event carries, and what it does not

The event carries:

- the time, the operation and the portal
- the reason code, for example `ABOVE_CEILING` or `FAILED`
- the value id that the line names
- the access context, which tells who asked

The event never carries:

- the label
- the detail
- an exception or its message, because either can hold plaintext

The value id and the access context go against the rule for telemetry. James accepted this on
2026-10-03, for one reason: the listener is application code in the same process. The application
made the access context and already holds the occluded reference. An observation goes to a tracing
backend that has no encryption and no access control. An event does not, unless the application
sends it there.

### Spring: the starter publishes the `RefusalEvent` synchronously

This follows the Spring Framework reference ("Standard and Custom Events", fetched 2026-10-03).
It says: "by default, event listeners receive events synchronously." The listener, not the
publisher, chooses asynchronous delivery, with `@Async`. Spring Security and the Actuator audit
events also publish synchronously. That precedent comes from memory and was not checked again for
this document.

The auto-configuration registers this listener, with no `async`:

```java
RefusalListener listener = publisher::publishEvent;
```

Spring wraps a plain object in a `PayloadApplicationEvent`, so `RefusalEvent` does not extend
`ApplicationEvent`, and core has no Spring dependency. An application listens like this:

```java
@EventListener
@Async
void on(RefusalEvent event) {
  if (event.reason().equals("ABOVE_CEILING")) {
    alerts.raise(event.portal(), event.context());
  }
}
```

- **The application chooses asynchronous delivery.** With `@Async` and `@EnableAsync`, Boot's
  auto-configured `AsyncTaskExecutor` delivers the event. If `spring.threads.virtual.enabled=true`,
  that executor uses virtual threads.
- **Occlude adds no executor and no setting.** `spring.task.execution.*` controls the pool, the
  thread names and the wait at shutdown.
- **A listener without `@Async` slows the request.** That is Spring's normal behaviour. The guide
  must say so, and recommend `@Async` for a listener that does slow work.
- **The application can make every event asynchronous.** It defines an
  `applicationEventMulticaster` bean with a `taskExecutor`. That is a decision for the whole
  application, and Occlude does not make it.

The starter's listener is `@ConditionalOnMissingBean(RefusalListener.class)`. An application that
defines its own `RefusalListener` bean replaces it.

**Warning for the guide.** A synchronous listener runs inside the caller's transaction. Thus, a
`@TransactionalEventListener` does not run when the caller's transaction rolls back. The line stays
in the trail, because `JdbcStorage` commits it on its own connection. The alert is then lost for a
refusal that really happened. The guide must say: use `@EventListener` for a `RefusalEvent`, not
`@TransactionalEventListener`.

### Logs

The observability design says: "Nothing is logged per operation. The trail is the log." This design
keeps that rule. Core does not log refusals.

An application that wants refusals in its log adds a listener that logs them. The Spring guide will
show one in three lines. This gives the nessy-ap desk the log line that it asked for, and the
application decides the level.

## What does not change

- The trail is still the only record, and the application still cannot turn it off.
- A listener cannot change an outcome. A listener that throws has no effect.
- The observations keep the same keys. No value id or context attribute goes into telemetry.
- Core starts no threads.
- With no listener, nothing new happens.

## Decided

James decided these on 2026-10-03:

1. **The event carries the value id and the access context**, for the reason given above.
2. **Core does not log refusals.** Applications log from a listener. This replaces an earlier idea
   to log `FAILED` at WARN, which went against the observability design.
3. **Refusals only.** Occlude publishes no event for an allowed operation. The allowed operation is
   the normal case, and the trail already has it.
4. **The names are `RefusalEvent`, `RefusalListener` and `Bindings.onRefusal(...)`.**
5. **Delivery belongs to the listener.** Core calls the listener synchronously, and
   `RefusalListener.async(Executor)` moves delivery off the request thread. An earlier draft had
   core start a virtual thread for each event, with a limit of 256 deliveries in flight. That
   draft gave core its first threads and gave Occlude a throttle that belongs to the listener.
6. **The Spring starter publishes synchronously**, as Spring's guidance says. The application
   chooses asynchronous delivery with `@Async`, on Boot's executor. An earlier draft gave the
   starter an executor of Occlude's own, with virtual threads. That draft took a decision from the
   application that Boot already gives to `spring.threads.virtual.enabled`.

## Testing

- **Core:**
  - Use a plain listener, which is synchronous.
  - Assert one event for each refusal reason of each portal kind, `NOT_AS_SIGNED` and `UNREADABLE`
    included.
  - Assert no event for an allowed operation, and none for an exception that the trail does not
    record.
- **No sensitive data:** run every refusal. Then assert that no event holds a label, a detail or an
  exception message.
- **A listener that throws:** assert that the outcome and the trail do not change.
- **`async`:**
  - Assert that the listener runs on the executor's thread. Use a latch, not a sleep.
  - Close the executor. Then assert that a refusal still returns normally and that the trail has
    the line.
- **Spring:** use `ApplicationContextRunner`.
  - Assert that an `@EventListener` for `RefusalEvent` receives the event on the request thread.
  - Assert that an `@Async` listener receives it on another thread. Use a latch, not a sleep.
  - Assert that a `RefusalListener` bean from the application replaces the starter's.
