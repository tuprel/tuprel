# Matriz de Funcionalidades Planeadas

Este documento separa o que deve existir no produto estável do que não deve bloquear a primeira release.

O subconjunto inicial de schema definido no RFC-001 já está implementado em
`tuprel-schema`, com `validate` e `format` disponíveis em `tuprel-cli`. Os
itens abaixo continuam a descrever o roadmap do produto e não são todos
funcionalidade disponível nesta fase.

## Tuprel 0.1 - Foundation preview

- parser e validator básicos de `schema.tuprel`;
- models, scalars, enums, ids, unique e indexes básicos;
- geração de modelos Java e client mínimo;
- PostgreSQL connection/runtime mínimo;
- `create`, `findUnique`, `findMany`, `update`, `delete` básicos;
- SQL sempre parametrizado;
- CLI `validate`, `format`, `generate`;
- testes em PostgreSQL real;
- nenhum compromisso de compatibilidade binária.

## Tuprel 0.5 - Developer preview

Inclui 0.1 e acrescenta:

- relações 1:1, 1:N e N:N;
- filtros compostos e ordenação;
- select/include explícitos;
- offset e cursor pagination;
- transactions;
- batch operations;
- query timeout;
- SQL preview;
- raw SQL parametrizado;
- migration engine dev/deploy/status;
- history, checksums, migration lock e drift detection;
- `db pull` PostgreSQL;
- Spring Boot starter;
- Gradle integration;
- seed.

## Tuprel 1.0 - Primeira versão estável

Além do anterior:

- contrato de schema versionado;
- API Java pública estabilizada;
- Maven integration;
- JSONB, arrays, UUID e tipos PostgreSQL prioritários;
- optimistic locking;
- streaming com lifecycle seguro;
- locks de linha suportados explicitamente;
- explain/query diagnostics;
- logging estruturado e integração de observabilidade definida;
- migration safety avançada para alterações com risco de data loss;
- mensagens de erro e diagnostics de alta qualidade;
- documentação completa e exemplos compiláveis;
- compatibilidade e matriz de testes definida;
- artefactos de release assinados e supply-chain checks.

## Depois de 1.0

Candidatos, não promessas:

- Studio gráfico;
- R2DBC/reactive;
- outros dialectos;
- read replicas/routing;
- schema diff visual avançado;
- tooling de IDE/LSP;
- query performance advisor;
- cloud features.

Nenhum item desta secção deve contaminar a arquitectura 1.0 sem necessidade comprovada.
