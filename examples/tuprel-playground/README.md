# Tuprel Playground — PostgreSQL local, Java 21

Aplicação consumidora independente para experimentar o Tuprel da Fase 5,
integrada em `main`. Sem Docker ou Testcontainers. A fundação offline F6-001
não substitui o bootstrap por migrations: essa adopção terá tarefa própria,
baseline revisto e preservação dos dados existentes.

## Preparação no Windows

1. Instalar/seleccionar JDK 21 no IntelliJ e para a JVM do Gradle.
2. Confirmar o serviço PostgreSQL com `Get-Service *postgres*`, ou localizar
   a instalação e executar `pg_isready.exe -h localhost -p <porta-real>`.
3. Criar, com uma conta administrativa e **uma única vez**, uma base vazia
   chamada `tuprel_devtest`, cujo proprietário seja o utilizador de testes.
   Pode usar pgAdmin. A aplicação não cria/apaga bases nem altera utilizadores.
   O utilizador precisa de CONNECT e CREATE nessa base; não precisa de superuser.

Abrir `.env` nesta pasta no IntelliJ e preencher a password. O ficheiro local
já foi preparado para a base indicada; noutro checkout, copiar `.env.example`
para `.env`. Nunca colocar a password em `.env.example`.

```dotenv
TUPREL_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/tuprel_devtest
TUPREL_TEST_DB_USER=tuprel_test_user
TUPREL_TEST_DB_PASSWORD=
```

Não é preciso configurar variáveis no PowerShell. A aplicação e as suites de
integração internas carregam o mesmo `examples/tuprel-playground/.env` no
processo Java. O ficheiro está excluído do Git. O agente não precisa de abrir
nem mostrar o seu conteúdo. Não activar logs `--debug` com credenciais reais.

O carregador aceita as três entradas acima, comentários em linhas próprias,
linhas vazias e valores opcionalmente entre aspas simples/duplas. Os valores
são literais: `#`, `=`, `$` e barras não executam comandos nem interpolação;
aspas permitem conservar espaços nas extremidades. Não suporta valores
multilinha, `export`, comentários no fim de atribuições ou substituição de
variáveis. Entradas desconhecidas, duplicadas ou malformadas falham sem mostrar
valores. Variáveis de ambiente não vazias, quando existirem, têm precedência.
Não se procuram outros ficheiros `.env` nas pastas superiores.

No painel Gradle do IntelliJ executar, por ordem:

1. `checkConnection` — SELECT de diagnóstico JDBC, sem alterar dados/tabelas.
2. `run` — aplicação e verificações funcionais completas.

Os mesmos comandos podem ser executados num terminal, a partir da raiz:

```text
.\gradlew.bat -p examples/tuprel-playground checkConnection
.\gradlew.bat -p examples/tuprel-playground run --dependency-verification strict
.\gradlew.bat check --dependency-verification strict
```

## IntelliJ

Abrir **esta pasta**, `examples/tuprel-playground`, como projecto Gradle.
O `includeBuild("../..")` liga os módulos do repositório sem publicação Maven.
Seleccionar JDK 21 tanto no Project SDK como na Gradle JVM e seleccionar o
Wrapper incluído neste exemplo, fixado na mesma versão do Wrapper da raiz.
Também é possível abrir a raiz e ligar este build como projecto Gradle adicional.

Executar `generateClient` no painel Gradle e sincronizar o projecto. O cliente
fica em `build/generated/sources/tuprel/main`, integrado no source set; não se
edita nem se versiona. `compileJava` gera e valida o schema automaticamente.

Para correr classes directamente, começar por `dev.tuprel.playground.CheckConnection`
e depois `dev.tuprel.playground.Playground`. Na Run Configuration, seleccionar
JDK 21 e **Working directory = esta pasta, `examples/tuprel-playground`**, para
que o caminho relativo `.env` seja inequívoco. Não é necessário guardar passwords
na Run Configuration. As tasks Gradle já fixam essa pasta automaticamente.

## O que comprova

- CLI real `validate` e `generate`, seguida de compilação Java 21.
- Criação em transacção de cliente, dois produtos, encomenda e dois itens.
- `createMany`, consultas tipadas, filtros, ordenação, offset e cursor pagination.
- Includes explícitos de cliente, itens e produtos, sem lazy loading.
- `updateById` com versão, conflito de optimistic locking e `deleteById`.
- Rollback completo e rollback de savepoint com continuação da transacção exterior.
- Unicidade (`23505`), FK (`23503`), CHECK (`23514`) e rejeição de campo
  obrigatório pelo input gerado, antes de executar SQL.
- SQL preview com placeholders, sem imprimir binds, credenciais ou causas JDBC.

Cada verificação falha com exit code 1 se o resultado não for o esperado. `OK`
significa que o percurso inteiro executou contra PostgreSQL. Apenas compilar o
exemplo não valida o acesso à base de dados.

As execuções seguintes adicionam um cliente e encomenda novos com UUIDs únicos.
Os dados ficam guardados para inspecção no pgAdmin/IntelliJ Database:

```sql
SELECT * FROM tuprel_playground."Customer";
SELECT * FROM tuprel_playground."Product";
SELECT * FROM tuprel_playground."SalesOrder";
SELECT * FROM tuprel_playground."OrderItem";
```

## Limitações deliberadas

`src/main/resources/bootstrap.sql` é **DDL manual temporário**, executado em
transacção exclusivamente no schema `tuprel_playground` da base dedicada.
O motor de migrations ainda não existe. `CREATE TABLE IF NOT EXISTS` não
compara/evolui tabelas existentes, não detecta drift, não guarda histórico e
não aplica checksums. As CHECK constraints são SQL manual, não geradas pelo
schema Tuprel. Se modificar o schema, tem de rever o DDL explicitamente.

O exemplo não apaga schemas, tabelas ou dados de execuções anteriores. Só elimina
o produto temporário que criou para demonstrar DELETE. As relações escrevem-se
pelas FKs escalares; não há escrita aninhada de grafos nem cascatas implícitas.
Não há artefactos Tuprel publicados nem plugin Gradle de produto nesta fase:
as tasks `validateSchema` e `generateClient` são integração local deste exemplo.

O composite build usa também as dependências de build do repositório. Por isso,
`gradle/verification-metadata.xml` é uma cópia exacta do catálogo verificado da
raiz, comparada pela CI; ao actualizar dependências, actualizar ambas as cópias.
As versões efectivamente consumidas pelo exemplo têm lockfile próprio. O build
executa também dois testes unitários de segurança do target local, sem precisar
de PostgreSQL; a validação funcional completa é feita por `run`.

As três suites internas de integração usam o mesmo ambiente, com schemas
temporários próprios. Consultar [Build e Testes](../../docs/development/BUILD_AND_TEST.md).
