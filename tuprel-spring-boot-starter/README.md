# Spring Boot starter

This development module integrates Spring Boot 4.1.2 in the source build.
Production classes target Java 17 using the internal JDK 21 toolchain.
Java 21 remains the effective public minimum until ADR-0024 certification
and activation are complete. It configures a `TuprelDatabase` for an application with one
`DataSource` or an explicit primary candidate. An application-supplied
`TuprelDatabase` takes precedence. Keep pool configuration and shutdown in
the application.

Inject the database into your application code and construct the client
generated for your own schema:

```java
TuprelClient client = new TuprelClient(database);
```

The starter has no JPA dependency and performs no migrations or queries
during startup. Use Tuprel's explicit transaction API for work owned by
Tuprel. With a plain application `DataSource`, this transaction can commit
independently even if an enclosing Spring `@Transactional` method rolls back.
The external PostgreSQL fixture verifies that boundary. Spring-managed
transaction participation requires a separate contract.

The external fixture also exercises a `TransactionAwareDataSourceProxy`.
The runtime refuses the Spring-bound connection because ordinary operations
require autocommit. Spring retains the connection and rolls back its pending
JDBC write when the failure leaves the annotated method. The
[proposed ownership contract](../docs/adr/ADR-0023-spring-jdbc-ownership.md)
describes the separate opt-in mode needed for participation.

Run `./gradlew :tuprel-spring-boot-starter:integrationTest --dependency-verification strict`
to compile and launch a separate Spring Boot application against the starter
JAR. The live test uses the existing isolated PostgreSQL 17 test profile. Maven publication and support for other Boot lines remain pending.

## External REST and LTS fixture

The [products API](../examples/spring-products-api/README.md) is a standalone
Maven application with generated models, validation and real HTTP/PostgreSQL
tests. It has no authentication and is limited to local development and tests.
Its SQL setup is explicit; the starter never creates tables on startup.

```powershell
pwsh -File test-support/distribution/Verify-SpringLts.ps1 `
  -Maven $maven -Jdk17 $jdk17 -Jdk21 $jdk21 -Jdk25 $jdk25
```

The harness stages the same four library artifacts once, uses Maven 3.9.16
with isolated caches on each JDK, generates and compiles with release 17,
then launches the executable JAR after Maven exits. It replays the Java 17
application JAR on all three JVMs without recompilation. Linux CI also
exercises a pinned Java17-only container. Local Windows uses the approved
PostgreSQL test database without Docker. Database credentials use test-only
configuration; this is not a production `.env` loader API.

The ownership fixture covers startup without database access, DataSource
selection, application overrides, failed startup and pool closure. A context
without an application DataSource explicitly excludes Spring's own JDBC
auto-provisioning, so that test measures Tuprel's conditional configuration.
The transaction tests preserve independent Tuprel commit/rollback and Spring
JDBC commit/rollback, and the refusal of a Spring-bound proxy connection.
They do not certify participation in Spring transactions.
