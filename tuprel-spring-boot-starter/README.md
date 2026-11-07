# Spring Boot starter

This development module supports Spring Boot 4.1 on Java 21 in the source
build. It configures a `TuprelDatabase` for an application with one
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

The external fixture also exercises a statement-only adapter over Spring's
transaction-bound connection. Spring commits or rolls back its JDBC write and
the Tuprel write together. The fixture wires the adapter explicitly. It
supports evaluation of a future opt-in mode. Before exposing that mode, define
which transaction manager owns the connection, reject explicit Tuprel
transaction ownership in a Spring-bound scope, and verify propagation,
read-only transactions, timeouts and failure translation.

Run `./gradlew :tuprel-spring-boot-starter:integrationTest --dependency-verification strict`
to compile and launch a separate Spring Boot application against the starter
JAR. The live test uses the existing isolated PostgreSQL 17 test profile. Maven publication and support for other Boot lines remain pending.
