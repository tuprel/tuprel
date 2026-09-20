# Regras de Arquitectura

- O domínio central do Tuprel não depende de Spring, Quarkus, Micronaut, JPA ou Hibernate.
- Nenhum módulo pode depender de um módulo de integração para funcionar.
- Parsing, validação semântica, geração de código, runtime SQL e migrations são responsabilidades separadas.
- PostgreSQL-specific behavior deve ficar num adapter/dialect explícito.
- O modelo interno de schema deve poder ser testado sem uma base de dados.
- O renderer SQL deve receber uma representação estruturada, não strings concatenadas espalhadas pela aplicação.
- Evita singletons globais e estado mutável partilhado.
- Toda a dependência entre módulos deve apontar na direcção definida em `docs/architecture/MODULE_BOUNDARIES.md`.
- Uma mudança no grafo de módulos exige actualização do documento e, se significativa, ADR.
