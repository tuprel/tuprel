# Build Conventions

A Fase 0 deve estabelecer:

- Gradle Wrapper versionado;
- Kotlin DSL;
- Java toolchain 21;
- repositórios mínimos e centralizados;
- version catalog para versões externas quando fizer sentido;
- convention plugins apenas se reduzirem duplicação real;
- formatting e static analysis com configuração central;
- JUnit 5/JUnit Platform;
- layout e task de integration tests; Testcontainers é adicionado quando
  existir o primeiro teste cujo comportamento dependa de PostgreSQL;
- tasks separadas quando integration tests forem lentos;
- dependency locking;
- dependency verification após revisão do metadata bootstrap;
- build cache local e configuration cache activos após compatibilidade
  confirmada na raiz e em build-logic;
- reproducibilidade e timestamps controlados para artefactos de release.

Não seleccionar ferramentas apenas por popularidade. Cada plugin adicional aumenta supply-chain e manutenção.
