# Estratégia de Testes

## Pirâmide adaptada a um ORM

Um ORM precisa de mais testes de integração do que uma biblioteca puramente algorítmica, porque drivers, transactions, DDL e tipos fazem parte do comportamento real.

## 1. Unit tests

- lexer/parser units;
- semantic validation;
- naming rules;
- SQL AST transforms;
- bind ordering;
- type mapping puro;
- diagnostics/error model.

## 2. Golden tests

- schema -> diagnostics;
- schema -> generated Java;
- query model -> SQL + binds;
- schema diff -> migration representation.

Golden files precisam de revisão humana; não devem ser actualizados automaticamente só porque o teste falhou.

## 3. Generated-code compilation tests

O output do codegen deve ser compilado numa test fixture. Isto impede que snapshots bonitos escondam Java inválido.

## 4. PostgreSQL integration tests

Usar PostgreSQL real: local por omissão, numa base dedicada `tuprel_devtest`,
com variáveis `TUPREL_TEST_JDBC_URL`, `TUPREL_TEST_DB_USER` e
`TUPREL_TEST_DB_PASSWORD` no ambiente ou no `.env` ignorado de
`examples/tuprel-playground`. Variáveis de ambiente não vazias têm precedência.
O carregamento do ficheiro ocorre apenas no processo Java, sem logs de valores
nem configuração de segredos no Gradle. Testcontainers permanece disponível por opção
explícita (`-PtuprelTestDatabase=container`), usada na CI. Não há fallback
automático nem testes ignorados por falta de configuração. As suites existentes
executam os mesmos casos em ambos os modos, num schema aleatório por suite,
eliminado no final. Nunca limpar tabelas de schemas pré-existentes.

Cobrir:

- CRUD;
- relations;
- transactions;
- isolation/locks;
- JSONB/arrays/UUID;
- constraints;
- pagination;
- timeouts/cancellation;
- streaming/lifecycle;
- introspection;
- migrations.

## 5. Migration tests

Manter fixtures versionadas e testar:

- database vazia -> latest;
- versão N -> N+1;
- várias migrations sequenciais;
- checksum alterado;
- drift;
- duas instâncias concorrentes;
- falha a meio;
- alteração destrutiva;
- NOT NULL em tabela com dados;
- rename seguro vs drop/create;
- índices e constraints.

## 6. Security/adversarial tests

Inputs com:

- `'`, `"`, `;`, `--`, `/* */`;
- unicode e caracteres de controlo;
- nomes reservados PostgreSQL/Java;
- nomes extremamente longos;
- `../` e paths inesperados;
- payloads em metadata introspectada.

## 7. Compatibility tests

Java baseline + JDKs LTS/actuais escolhidos na matriz. PostgreSQL versions suportadas em `COMPATIBILITY_MATRIX.md`.

## 8. Performance

Usar JMH/microbenchmarks apenas para hot paths adequados e workloads de integração para throughput real. Benchmarks têm baseline versionada e ambiente documentado. Não bloquear toda PR em benchmarks instáveis; usar regressão controlada em workflow dedicado quando existir.
