# Tuprel ORM

> Modern relational data toolkit for Java.

> Estado: fundação da Fase 0 concluída; a Fase 1 já disponibiliza o parser,
> validator, formatter e a CLI inicial de schema. Ainda não existe uma release
> utilizável.

O código está disponível sob a [Apache License 2.0](LICENSE) (`Apache-2.0`).
Copyright 2026 Mamadu Sama.

Tuprel ORM pretende ser uma plataforma de persistência para Java orientada a schema, geração de código type-safe, queries explícitas, migrações auditáveis e integração profissional com o ecossistema Java.

Este repositório encontra-se preparado para desenvolvimento assistido por
Codex e Claude Code. Começa por `START_HERE.md`.

Para construir e verificar o repositório, consulta `docs/development/BUILD_AND_TEST.md`.

Para a linguagem de schema implementada na Fase 1, consulta
`docs/rfcs/RFC-001-schema-language.md`.

## Objectivos de engenharia

- API Java simples sem sacrificar previsibilidade.
- PostgreSQL como primeira implementação de referência.
- Modelos declarativos e código gerado type-safe.
- Migrações seguras, verificáveis e com drift detection.
- Integração com aplicações Java puras, Spring Boot, Gradle e Maven.
- Observabilidade suficiente para compreender SQL, timings e problemas de desempenho.
- Segurança e integridade de dados como requisitos do produto, não como extras.

## Estado do nome

Tuprel é o nome escolhido para o projecto. Foi feita uma pesquisa preliminar de disponibilidade; não houve clearance legal ou de marca. O desenvolvimento público do repositório é permitido, mas ainda não há release utilizável nem publicação de artefactos configurada. Consulta `docs/product/NAMING_AND_LEGAL.md`.
