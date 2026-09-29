# Occlude

[![CI](https://github.com/jwcarman/occlude/actions/workflows/maven.yml/badge.svg)](https://github.com/jwcarman/occlude/actions/workflows/maven.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https://raw.githubusercontent.com/jwcarman/occlude/main/pom.xml&query=//*[local-name()='maven.compiler.release']/text()&label=Java&color=orange)](https://openjdk.org/)

[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_occlude&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_occlude)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_occlude&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_occlude)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_occlude&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_occlude)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_occlude&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=jwcarman_occlude)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_occlude&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jwcarman_occlude)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_occlude&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jwcarman_occlude)

**Opaque references to sensitive values, for Java.**

Some values should not simply become text: personal and regulated data, credentials, untrusted
input from outside. Once such a value is a `String` in your application, nothing can tell you where
it went — a log line, an event, a prompt.

Occlude takes custody of the value and hands back an **occluded reference**. It travels anywhere,
because holding one is not permission to read it. Turning it back into a value is the one checked
operation, and it always names where the value is going.

```java
Occluded<Mail> mail = customerMail.occlude(incoming);   // the real thing stays here

vendorLlm.reveal(mail);        // Denied  — above that sink's ceiling
quarantinedLlm.reveal(mail);   // Allowed
```

Built on Denning's lattice model and on the object-capability model: **authority is held, never
looked up**. There is no registry and no `charter.get("customer-mail")`. Code can perform an
operation because something handed it the portal that performs it — so "what can this class do?" is
answered by reading its constructor parameters.

## Install

```xml
<dependency>
  <groupId>org.jwcarman.occlude</groupId>
  <artifactId>occlude-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

That is the Spring Boot path; add a Postgres driver beside it. Plain Java, the BOM, and trying it
in memory are in [Getting Started](https://jwcarman.github.io/occlude/guides/getting-started/).
Occlude's encryption and serialisation come from [codec](https://github.com/jwcarman/codec), a
small library it depends on.

## In one page

An application declares **axes** — the questions it asks about every value, such as a tenant, a
sensitivity, whether it was vouched for — and every value carries a **label** answering them. Every
place a value may go declares a **ceiling**, and reading is decided against both. Deriving a value
joins its parents' labels, so an ordinary derivation *cannot* weaken one, and a value made from two
tenants' data carries a mixture no ceiling admits.

```java
DefaultCharter charter = new DefaultCharter(Axes.of(TENANT, SENSITIVITY));

Occlude<Mail> customerMail = charter.source("customer-mail", MAIL,
    ctx -> Label.of(TENANT, ctx.get("tenant").orElseThrow()).with(SENSITIVITY, PERSONAL));
Reveal<Mail> supportDesk = charter.reveal("support-desk",
    ctx -> Ceiling.of(TENANT, Constraint.atMost(ctx.get("tenant").orElseThrow()))
                  .with(SENSITIVITY, Constraint.atMost(PERSONAL)), MAIL);

charter.bind(Bindings.of(storage).withIdentity(currentAccess));
```

A **charter** is where authority is declared, and each declaration hands back the one object able
to perform it — a **portal**. Holding the portal is the only way to perform the operation; nothing
can look one up. Every operation writes a line to a tamper-evident **record**, allowed and refused
alike. Storage is Postgres, and everything it keeps is encrypted and signed.

| module | what it is |
|---|---|
| `occlude-api` | the portals and results application code holds |
| `occlude-core` | the charter, labels and ceilings, and `MemoryStorage` for tests |
| `occlude-jdbc` | encrypted, signed storage in Postgres 13+ |
| `occlude-spring-boot-starter` | Spring Boot wiring, with the JDBC store |
| `occlude-bom` | versions for all of the above |

## Read the docs

The [documentation site](https://jwcarman.github.io/occlude/) is the manual:

- [Getting Started](https://jwcarman.github.io/occlude/guides/getting-started/) — a worked example,
  from axes to a first reveal
- [Concepts](https://jwcarman.github.io/occlude/concepts/labels/) — labels, ceilings, portals,
  deriving, the record, erasing
- [Spring Boot](https://jwcarman.github.io/occlude/guides/spring-boot/),
  [Storage](https://jwcarman.github.io/occlude/guides/storage/) and
  [Operating a Store](https://jwcarman.github.io/occlude/guides/operating/)
- [What Occlude does not do](https://jwcarman.github.io/occlude/limits/) — the page to read first if
  you are deciding whether it fits

## Building

```
./mvnw clean verify
```

Java 25. The JDBC tests need Docker, for Testcontainers.
