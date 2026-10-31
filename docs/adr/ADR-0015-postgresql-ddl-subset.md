# ADR-0015 — Gramática DDL offline e preview determinístico

Estado: **aceite** em 2026-10-31 por aprovação explícita do utilizador.
Contrato de gramática/AST/preview da F6-003A1. Não implementa nem disponibiliza
executor; a tarefa actual limita-se à integração das entregas offline.

## Contexto, problema e restrições

ADR-0014 exige um parser completo para um subconjunto pequeno, sem chamar SQL
arbitrário seguro por regex/blacklist. Não existe parser DDL na base actual;
o parser de schema.tuprel analisa outra linguagem e não é recriado/reutilizado
como se compreendesse SQL. Reutilizam-se SqlIdentifier e MigrationFile.

Java 21, sem dependências externas novas, sem ficheiros/connections/SQL em DB.
Código PostgreSQL permanece no adapter existente do #28, não no domínio puro.
O adapter exporta a dependência de SqlIdentifier porque o AST público a utiliza;
é uma mudança de scope da aresta existente, sem módulo/dependência externa nova.
O PR é empilhado sobre #28 para evitar duplicar o seu módulo/modelos; só poderá
integrar depois da dependência e da aprovação específica deste contrato.

## Decisão

Gramática fechada, aplicada à totalidade do MigrationFile v1 (UTF-8/LF e limites
de bytes já validados). Keywords ASCII case-insensitive; nomes **sempre entre
aspas duplas**, com o contrato ASCII/63 caracteres de SqlIdentifier. Preservar
case, sem truncar/normalizar. Aspas duplicadas são lexadas, mas o identificador
resultante com aspas internas é recusado pelo contrato portátil existente.

```ebnf
program       = statement, ";", { statement, ";" } ;
statement     = create_table | add_column | create_index ;
table         = identifier, ".", identifier ;
create_table  = "CREATE TABLE", table, "(", column, { ",", column }, ")" ;
column        = identifier, type, [ nullability_and_pk ] ;
nullability_and_pk = "NOT NULL" | "PRIMARY KEY"
                  | "NOT NULL PRIMARY KEY" | "PRIMARY KEY NOT NULL" ;
add_column    = "ALTER TABLE", table, "ADD COLUMN", identifier, type, [ "NULL" ] ;
create_index  = "CREATE INDEX", identifier, "ON", table,
                "(", identifier, { ",", identifier }, ")" ;
type          = "UUID" | "BOOLEAN" | "SMALLINT" | "INTEGER" | "BIGINT"
              | "TEXT" | "DATE" | "TIMESTAMPTZ" | "TIMESTAMP WITH TIME ZONE"
              | qualified_builtin ;
qualified_builtin = '"pg_catalog"', ".", ('"uuid"' | '"bool"' | '"int2"'
                  | '"int4"' | '"int8"' | '"text"' | '"date"' | '"timestamptz"') ;
```

Uma tabela tem 1–256 colunas distintas e no máximo uma PK inline; PK implica
NOT NULL. Index tem 1–32 colunas distintas, sem UNIQUE/CONCURRENTLY/expressões/
predicados/opclasses. O nome do index é unqualified: PostgreSQL coloca-o no
schema da tabela, não permite schema no basename de CREATE INDEX.

Whitespace ASCII e comentários --/block aninhados são trivia lexada, não split
por ponto e vírgula. Strings, dollar quotes, escapes Unicode, directivas psql,
funções, defaults, references/FKs, checks e todo o SQL fora da gramática falham.
Não há IF NOT EXISTS, temporárias, unlogged, herança/partições ou transacções.

Limites: 256 statements, 32768 tokens incluindo EOF, 32 níveis de block comment;
lexer iterativo e listas limitadas, sem recursão proporcional ao input. Sem
statement vazio ou prefixo parcialmente aceite. Draft só com comentário é EMPTY.
Diagnóstico por categoria/linha/coluna UTF-16, sem token/source/SQL sensível.

AST imutável não representa SQL raw/literais/expressões. Preview é um renderer
do AST, LF determinístico e tipos explicitamente pg_catalog-qualified, evitando
resolver tipos pelo search_path. Comentários omitidos não alteram a obrigação
futura de vincular os **bytes originais** por checksum. Reconhecimento sintáctico
não verifica existência/permissões/locks/drift nem aprova aplicação.

Esta proposta define os tokens de tipos como built-ins, mesmo se existir um
tipo de utilizador homónimo noutro search_path. O futuro executor deve consumir
o preview canónico aprovado, não reabrir/executar o source raw com outra
resolução de tipos. Isso aplica o artefacto analisado; não gera DDL de schema.
Gramática `postgresql-ddl-v1-proposal` e renderer `postgresql-ddl-preview-v1-proposal`
devem ser vinculados pelo digest da A2. A semântica e a escolha de execução
seguem o ADR-0017 aprovado antes de existir runner. Os identificadores de versão
com sufixo `-proposal` são preservados como bytes normativos deste contrato;
a aceitação não muda a gramática, renderer ou futuros digests.

## Opções, consequências e riscos

- Nomes sempre quoted reduzem ergonomia manual, mas evitam duplicar uma lista de
  reserved words/folding/truncation PostgreSQL. Expansão exige novo contrato.
- Tipos mínimos não cobrem todos os scalars do ORM. Tipos/expressões desconhecidos
  permanecem bloqueados, sem renderização aproximada.
- Parser externo completo teria custo de dependências/licença/manutenção e não
  substituiria allowlist de operações. Regex/split rejeitados por não analisarem
  contexto lexical. Este parser não é PostgreSQL completo.
- Prefixos DROP/TRUNCATE/DELETE/UPDATE são sinalizados como DESTRUCTIVE_PREFIX;
  não é detecção exaustiva de data loss. Todo o desconhecido é bloqueado, mesmo
  sem esse sinal. Classificação de riscos/target/digest pertence à F6-003A2.
- CREATE INDEX normal pode bloquear writes e consumir recursos; ADD COLUMN
  nullable e CREATE TABLE também adquirem locks. Sintaxe aceite nunca é SAFE.

## Compatibilidade, segurança e validação

Não modifica v1/bytes/checksums, comandos existentes ou histórico. Nenhum comando
novo exposto na CLI. Sem mudanças de Java/dependências/boundaries. Classes novas
são APIs de preview do subconjunto aceite; não existe método de execução.

Testes golden e round-trip de AST/preview, todos os tipos, comentários aninhados,
input adversarial/trailing SQL, nomes inválidos, duplicações, PKs, limites e
imutabilidade. Sem executar DDL ou ligar à base para validar esta proposta.
Sem prometer que preview compila contra todos os PostgreSQL; alvo do contrato PG17.

Fontes: [lexical PG17](https://www.postgresql.org/docs/17/sql-syntax-lexical.html),
[CREATE TABLE](https://www.postgresql.org/docs/17/sql-createtable.html),
[CREATE INDEX](https://www.postgresql.org/docs/17/sql-createindex.html).
