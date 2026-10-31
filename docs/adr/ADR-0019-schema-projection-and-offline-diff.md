# ADR-0019 — Projecção conservadora de schema.tuprel e contrato de diff offline

Estado: **aceite** por aprovação explícita do utilizador. Complementa ADR-0018.
Não amplia a gramática SQL nem autoriza novas operações de escrita.

## Contexto e fronteiras

O diff lógico aceite compara estados do AST DDL. Para comparar o schema do
programador com migrations existentes é necessário mapear todos os atributos
relevantes ou recusar a representação inteira. O frontend suporta mais do que
a gramática DDL aceite. Nenhum atributo desconhecido pode desaparecer do diff.

Usar uma dependência API directa migrate-postgresql -> schema para consumir
ValidatedSchema no projector PostgreSQL. É uma alteração explícita da aresta
futura migrate -> schema prevista no ADR-0013: a projecção física depende do
dialecto e fica no adapter, mantendo migrate puro e sem acrescentar uma
representação intermédia redundante. Schema continua independente; sem ciclo,
driver CRUD, runtime, codegen ou dependência externa nova. Reutilizar
SchemaCompiler; a CLI não implementa projecção nem compara modelos.

API: PostgresqlSchemaProjector.project(SqlIdentifier, ValidatedSchema)
devolve SchemaProjection com snapshot() opcional e diagnostics() imutáveis.
Qualquer diagnóstico torna o snapshot ausente. Cada diagnóstico expõe categoria
e SourcePosition de início/fim, sem reter source, nomes inválidos, expressões ou configuração.
SourceSpan retém SourceText; posições destacadas cumprem o requisito de não conservar source.
A dependência Gradle é api porque ValidatedSchema e SourcePosition fazem parte das assinaturas públicas.
O projector constrói nós do AST existente e reutiliza SchemaSnapshot para os
invariantes finais. Não cria uma segunda linguagem, parser ou modelo de tipos.

## Projecção completa ou recusada

Modelo/field mapeiam para tabela/coluna com o nome exacto do schema, como o
metadata actual do codegen. Um schema alvo explícito qualifica todos os nomes.
Sem snake_case, normalização, truncation ou leitura de datasource/env.

| Scalar Tuprel | Tipo do AST DDL aceite |
|---|---|
| UUID | UUID |
| Boolean | BOOLEAN |
| Short | SMALLINT |
| Int | INTEGER |
| Long | BIGINT |
| String | TEXT |
| LocalDate | DATE |
| Instant | TIMESTAMPTZ |

Required/optional determinam NOT NULL/nullable; @id é PK inline. @version
não inventa um default: só a coluna Int/Long declarada é representada.
Defaults, @unique/@@unique, enums, relações, listas, Float/Double/Decimal,
Bytes/Json/LocalDateTime/LocalTime e quaisquer atributos físicos não admitidos
produzem diagnósticos e nenhum snapshot parcial. Declarações enum não são
interpretadas como CREATE TYPE. Configuração de generator/datasource não é DDL
nem uma origem de credenciais para diff; não é executada.

@@index([a, b]) representa index simples não único com chaves nessa ordem.
Nome gerado explícito: <Model>_<field1>_<field2>_idx, case preservado. O nome
tem de cumprir SqlIdentifier (máximo 63 ASCII); comprimentos excessivos e
colisões, inclusive colisões com tabelas/outros indexes, recusam o estado.
Não gerar hashes, sufixos aleatórios ou truncar para esconder a colisão.
O DSL actual não define nomes físicos de indexes. Um index manualmente nomeado
de modo diferente não é renomeado nem silenciosamente equiparado: o diff
reporta remoção/adição bloqueadas. Mapping explícito de nomes é trabalho futuro.

Diagnósticos estruturados por categoria/localização, sem SQL/source/defaults,
URLs ou mensagens cruas de exceptions. Qualquer diagnóstico impede snapshot.
Limites existentes de tabelas/colunas/chaves/objects/statements continuam activos.
Nomes reservados e targets de sistema são recusados conservadoramente.

## CLI planeada para F6-004C, ainda não implementada na F6-004B

`tuprel migrate diff --target-schema <name> [--schema <path>]` compara o replay
de **todos** os ficheiros do catálogo local tuprel/migrations com o schema
pretendido. --schema mantém o default tuprel/schema.tuprel. Não há configuração
DB, consulta de histórico, conexão, escrita de ficheiros, digest ou execução.
Fonte real de cada lado e ausência de observação física são sempre explicitadas.

Catalog discovery/bytes/order/links reutilizam MigrationDirectory. Cada script
é analisado por PostgresqlDdlParser; unknown/drafts ou replay incompleto recusam
toda a origem. SchemaCompiler é reutilizado para o destino. Paths inseguros e
symlinks não são seguidos; ajuda não lê ficheiros nem env. Um catálogo vazio é
uma origem lógica vazia, nunca afirma que uma base esteja vazia.

Saída sem ANSI, determinística: estado, diferenças exactas e riscos; SQL só se
todo o conjunto for representável. Sem preview parcial ou rename inference.
Exit 0 para igualdade lógica, 1 para diferenças integralmente previewáveis,
2 para usage/I/O/representação incompleta ou preview bloqueado. Schema inválido
tem diagnóstico seguro e exit 1, preservando o contrato de validação existente.
Não há --dry-run porque todo o comando é sem escrita. --help/-h sem I/O.

O comando só entra no help após implementação completa do contrato e testes
dos launchers PowerShell/Bash. Não afirmar drift ou estado aplicado/pending
com base num catálogo offline. Não usar diff como autorização de deployment.

## Trade-offs, testes e próximos gates

A primeira projecção é limitada mas completa dentro do seu subconjunto.
Recusas frequentes em modelos com defaults/relações são preferíveis a falsas
igualdades ou DDL aproximado. Novos tipos/expressões exigem contrato SQL próprio.
Nomes de indexes legíveis/limitados evitam política de hashes adicional; nomes
longos/customizados permanecem uma limitação explícita, sem alterar artefactos
aplicados para fazer o diff passar.

Testar todos os scalars, required/optional/PK/version, ordem de declaração,
indexes compostos/ordem/colisões/63 bytes, unsupported combinados com modelos
válidos, diagnósticos sem dados sensíveis, limites e concorrência. Golden
preview/reparse e fixtures PostgreSQL isoladas confirmam o mapping. CLI terá
testes de fontes inválidas, catálogo alterado/drafts/links, ausência de I/O em
help e execução real dos launchers Windows/Linux, sem credenciais.

F6-004B entrega projector e estes contratos; F6-004C entrega só depois a CLI.
F6-005 requer contrato separado de observação completa/conservadora de catálogo
e estados UNKNOWN/DRIFT. Propostas dev com escrita/aplicação necessitam aprovação
específica de contexto/revalidação, sem reset, reparação ou adopção do playground.
