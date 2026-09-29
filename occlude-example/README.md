# Occlude example: a billing support desk

A support desk that reads customer emails about disputed invoices. The email body never reaches a
vendor model, cardholder data never reaches any model, and an approver sees the last four digits of
a card only because a named derivation said so. `CharterConfiguration` declares every portal;
`DisputeService` uses them without ever holding an access context.

## Running it

```bash
./mvnw -pl occlude-example spring-boot:run -Dspring-boot.run.profiles=demo
```

The `demo` profile supplies a key and a root so it runs as checked out; without it the application
refuses to start, as a real deployment without keys would. Spring Boot's Docker Compose support
brings up Postgres and Grafana's all-in-one LGTM image from `compose.yaml`.

## Watching it in Grafana

Grafana is at <http://localhost:3000>. Every operation is a Micrometer observation exported over
OTLP, so in Explore:

- **Prometheus** has `occlude_operation_milliseconds_*` by `occlude_operation`, `occlude_portal`,
  `occlude_outcome`, `occlude_reason` and `error_type`, and `occlude_integrity_milliseconds_*` by
  `occlude_integrity_result` — the demo profile checks the store every minute.
- **Tempo** has each request's trace with the Occlude operations inside it; TraceQL
  `{ span.occlude.outcome = "refused" }` finds the requests that were refused something.

For example, refusals per portal and reason over the last five minutes:

```promql
sum by (occlude_portal, occlude_reason) (
  increase(occlude_operation_milliseconds_count{occlude_outcome="refused"}[5m]))
```

If the example's database container is already running from an earlier session, Spring Boot starts
nothing, so bring the rest up with `docker compose up -d` in `occlude-example` first.
