# Tuprel Playground — PostgreSQL local, Java 21

Aplicação consumidora independente para experimentar o Tuprel implementado na
branch de relações/transacções (PR #11). Não corresponde ainda ao código de
`main` enquanto esse PR não for integrado. Sem Docker ou Testcontainers.

## Preparação no Windows

1. Instalar/seleccionar JDK 21 no IntelliJ e para a JVM do Gradle.
2. Confirmar o serviço PostgreSQL com `Get-Service *postgres*`, ou localizar
   a instalação e executar `pg_isready.exe -h localhost -p <porta-real>`.
3. Criar, com uma conta administrativa e **uma única vez**, uma base vazia
   chamada `tuprel_devtest`, cujo proprietário seja o utilizador de testes.
   Pode usar pgAdmin. A aplicação não cria/apaga bases nem altera utilizadores.
   O utilizador precisa de CONNECT e CREATE nessa base; não precisa de superuser.

Configurar estas variáveis no ambiente do processo, usando o URL, a porta e o
utilizador reais. O URL deve apontar para `localhost`, `127.0.0.1` ou `[::1]`,
ter porta explícita e terminar em `/tuprel_devtest`, sem parâmetros.

```powershell
# Na raiz do repositório. Substituir o caminho, porta e utilizador pelos reais.
$env:JAVA_HOME = 'C:\caminho\para\jdk-21'
$env:TUPREL_TEST_JDBC_URL = Read-Host 'URL JDBC da base tuprel_devtest'
$env:TUPREL_TEST_DB_USER = Read-Host 'Utilizador de testes'
$securePassword = Read-Host 'Password de testes' -AsSecureString
$env:TUPREL_TEST_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password

.\gradlew.bat -p examples/tuprel-playground validateSchema
.\gradlew.bat -p examples/tuprel-playground build --dependency-verification strict
.\gradlew.bat -p examples/tuprel-playground run --dependency-verification strict

# Remover a password do ambiente quando terminar.
Remove-Item Env:TUPREL_TEST_DB_PASSWORD
```

Não passar credenciais em argumentos Gradle, no URL ou em ficheiros versionados.
Nenhum `.env` é lido. As variáveis são herdadas pelo processo Java lançado pelo
Gradle. Não usar `--debug`, que pode revelar informações do ambiente.

## IntelliJ

Abrir **esta pasta**, `examples/tuprel-playground`, como projecto Gradle.
O `includeBuild("../..")` liga os módulos do repositório sem publicação Maven.
Seleccionar JDK 21 tanto no Project SDK como na Gradle JVM e seleccionar o
Wrapper incluído neste exemplo, fixado na mesma versão do Wrapper da raiz.
Também é possível abrir a raiz e ligar este build como projecto Gradle adicional.

Executar `generateClient` no painel Gradle e sincronizar o projecto. O cliente
fica em `build/generated/sources/tuprel/main`, integrado no source set; não se
edita nem se versiona. `compileJava` gera e valida o schema automaticamente.

Executar a classe `dev.tuprel.playground.Playground` com as três variáveis
definidas na Run Configuration (evitar guardar a configuração com password).
Alternativamente, executar a task Gradle `run` num processo que herde esse
ambiente. O campo de environment variables do IntelliJ é local à configuração;
uma task Gradle separada não herda automaticamente as variáveis de outra Run Configuration.

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
