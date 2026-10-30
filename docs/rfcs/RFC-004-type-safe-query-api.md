# RFC-004: API de queries type-safe e cliente operacional gerado

- **Estado:** Aceite para o subconjunto implementado na Fase 4
- **Data:** 2026-09-29
- **Âmbito:** modelo estrutural de queries, API tipada do runtime, cliente
  gerado por model e renderização PostgreSQL
- **Relacionados:** RFC-001, RFC-002, ADR-0006, ADR-0009, ADR-0010

> **Revisão da Fase 5.** O RFC-003 acrescenta `include` e componentes de relação
> nos valores. O RFC-005 acrescenta transacções, locks, timeouts, streaming,
> `createMany`, `updateMany`, `deleteMany` e `updateById` com versão, e o
> ADR-0011 move o `ModelTable` gerado para `P.metadata`. Já não é verdade que
> cada operação execute um único statement: cada relação incluída acrescenta
> um statement por nível. Continua a não haver transacções implícitas.

## 1. Fronteira da decisão

A Fase 4 transforma os tipos gerados da Fase 2 num cliente operacional sobre o
runtime da Fase 3. Um developer lê e escreve dados de um model através de
métodos gerados e fortemente tipados, sem construir `SqlCommand`, `SqlValue`,
`SqlIdentifier` ou `RowMapper`. A API de baixo nível da Fase 3 continua
pública por baixo, para casos explícitos.

Entram: filtros por operador, composição lógica, predicados de `NULL`,
ordenação, projecções, `count`, `exists`, paginação por offset e por cursor,
preview de SQL e CRUD por identificador com `RETURNING`.

Ficam fora: relações e `include` (RFC-003, reservado), transacções, locks,
batch, streaming, `updateMany`/`deleteMany`/upsert, agregações além de
`count`, `@map`/`@@map`, enums nativos PostgreSQL, `Json` e listas.

## 2. Camadas

| Camada | Módulo | Responsabilidade |
|---|---|---|
| Modelo estrutural | `tuprel-sql` | `SqlQuery`, `SqlCondition`, `SqlOrder`, `InsertReturning`, `UpdateByIdReturning`; sem texto SQL nem política de dialecto |
| Dialecto | `tuprel-postgresql` | Citação de identificadores, placeholders, escape de padrões `LIKE`, limite de binds |
| Execução | `tuprel-runtime` | `TuprelDatabase.findMany/count/exists/preview/updateReturning` sobre o lifecycle JDBC da Fase 3 |
| API tipada | `tuprel-runtime`, package `dev.tuprel.runtime.query` | Colunas tipadas, condições, ordenação, query imutável, projecções, cursor, operações por model |
| Código gerado | projecto consumidor | Colunas por model, ordenação, cliente por model e `TuprelClient` |

`SqlQuery` escolhe uma de três selecções: `Columns` (colunas explícitas,
ordenável e paginável), `Count` e `Exists`. `Count` e `Exists` rejeitam
ordenação e paginação em vez de mudar de significado em silêncio. Não existe
`SELECT *` no caminho da API tipada. Comparações nunca aceitam
`SqlValue.Null`: `NULL` testa-se apenas com `NullCheck`.

## 3. Código gerado

Para o package raiz `P` e um `model User`, a geração acrescenta aos ficheiros
da Fase 2:

| Tipo | Package | Papel |
|---|---|---|
| `UserWhere` | `P.where` | Colunas tipadas para filtros e projecções |
| `UserOrder` | `P.order` | Colunas ordenáveis, expostas como `Sortable<User>` |
| `UserClient` | `P.client` | Operações explícitas sobre a tabela |
| `TuprelClient` | `P` | Ponto de entrada com um acessor por model operacional |

`UserWhere` expõe apenas campos que são colunas: escalares singulares e enums,
excepto `Json`. Relações, listas e `Json` continuam na metadata `UserFields`.
Um model com algum campo lista ou `Json` recebe `UserWhere` e `UserOrder`, mas
não recebe cliente nem entrada em `TuprelClient`, porque o seu valor não pode
ser materializado completamente. Isto é documentado no próprio
`TuprelClient`; não há omissão parcial de colunas.

A dependência do código gerado em `tuprel-runtime` e o mapeamento físico estão
decididos no ADR-0010. Tipos do runtime e do JDK introduzidos nesta fase são
importados pelo nome simples, excepto quando um model ou enum do schema tem o
mesmo nome simples. Nesse caso o código gerado usa o nome qualificado. Assim,
nomes comuns de models como `Query`, `Field` ou `Condition` continuam válidos.
Cada tipo gerado de topo tem `@javax.annotation.processing.Generated`, para
que análise estática configurada para ignorar código gerado o reconheça.

## 4. Colunas e operadores

`Field<M, T>` pertence a um model `M`. Todo o predicado devolve `Condition<M>`,
e `Query<M>.where` só aceita `Condition<M>`. Uma condição de outro model não
compila. Operações que não fazem sentido para um tipo não existem no seu tipo
de coluna.

| Tipo do schema | Coluna gerada | Operadores além de `eq`, `notEq`, `in`, `notIn`, `isNull`, `isNotNull`, `asc`, `desc` |
|---|---|---|
| `String` | `TextField<M>` | `contains`, `startsWith`, `endsWith` |
| `Short`, `Int`, `Long`, `Float`, `Double`, `Decimal` | `ComparableField<M, T>` | `lt`, `lte`, `gt`, `gte`, `between` |
| `Instant`, `LocalDateTime`, `LocalDate`, `LocalTime` | `ComparableField<M, T>` | `lt`, `lte`, `gt`, `gte`, `between` |
| `Boolean`, `UUID`, `Bytes`, enum | `Field<M, T>` | nenhum |

`TextField` não tem comparações de intervalo, porque a ordem de texto depende
da collation da base de dados.

## 5. Semântica

- **NULL:** comparações seguem a lógica de três valores do SQL e nunca
  correspondem a uma coluna `NULL`, incluindo `notEq` e `notIn`. `NOT`
  também não inclui essas linhas. `NULL` testa-se explicitamente com
  `isNull`/`isNotNull`. Valores de predicado nunca são `null`: a API rejeita-os
  com `NullPointerException`.
- **Texto:** `contains`, `startsWith` e `endsWith` são sensíveis a
  maiúsculas e tratam o argumento como texto literal. `%`, `_` e o carácter
  de escape são escapados pelo dialecto. O PostgreSQL usa `LIKE ? ESCAPE '!'`,
  que não depende de `standard_conforming_strings`.
- **Listas:** `in` e `notIn` exigem pelo menos um valor. O dialecto
  PostgreSQL rejeita statements com mais de 65 535 binds antes da execução.
- **Intervalos:** `between` é inclusivo nos dois limites.
- **Decimais:** comparam numericamente, independentemente da escala.
- **Enums:** são guardados como o nome da constante numa coluna de texto. Um
  valor lido que não é uma constante declarada falha no mapping, sem ecoar o
  valor.
- **Composição:** `and`, `or`, `not`, `Condition.allOf` e
  `Condition.anyOf` produzem estrutura, nunca texto. Chamar `where` várias
  vezes combina as condições com `AND`.

## 6. Query

`Query<M>` é imutável; cada método devolve uma nova query. `orderBy` acrescenta
termos, com precedência pela ordem de declaração. Sem ordenação, a ordem das
linhas não é especificada. O PostgreSQL coloca `NULL` no fim em ordem
ascendente e no início em ordem descendente. `skip` rejeita valores negativos,
`take` rejeita valores menores do que 1, e os dois são enviados como binds.

O cliente recebe a query através de `UnaryOperator<Query<M>>`, aplicado a uma
query vazia. Nada é executado enquanto a query é construída.

## 7. Operações do cliente

Cada método executa exactamente um statement, numa connection própria em
autocommit (ADR-0009):

| Método | Statement |
|---|---|
| `create(Create)` | `INSERT ... RETURNING` colunas do model |
| `findById(id)` | `SELECT` colunas `WHERE id = ?` |
| `findMany()` / `findMany(query)` | `SELECT` colunas com filtro, ordem e paginação |
| `findFirst(query)` | como `findMany`, com `LIMIT 1` |
| `findManyCursor(query)` | `SELECT` keyset com `LIMIT take + 1` |
| `select(projection[, query])` | `SELECT` apenas das colunas projectadas |
| `count()` / `count(query)` | `SELECT COUNT(*)` |
| `exists(query)` | `SELECT EXISTS (SELECT 1 ...)` |
| `updateById(id, Update)` | `UPDATE ... WHERE id = ? RETURNING` colunas |
| `deleteById(id)` | `DELETE ... WHERE id = ?` |
| `preview(query)` | nenhum; devolve o SQL que `findMany` executaria |

Inputs de escrita usam a presença da Fase 2: um campo ausente não é escrito e
um campo opcional presente com `null` escreve `NULL`. `create` e `updateById`
sem campos presentes são rejeitados. Actualizar o identificador é rejeitado.
`updateById` devolve vazio quando nenhuma linha tem o identificador, sem uma
segunda leitura que pudesse intercalar com outra escrita. Defaults do schema
continuam declarativos: valores por omissão vêm do `DEFAULT` da tabela e
aparecem no resultado de `RETURNING`.

`findUnique` não faz parte desta fase. Uma leitura única por campos `@unique`
exige tipos de filtro únicos, e um `LIMIT 1` esconderia duplicados. A leitura
por chave primária é `findById`.

## 8. Projecções

`Projection.of(fields, mapper)` selecciona colunas distintas, pela ordem
indicada. O mapper recebe um `ProjectedRow<M>` válido apenas durante o mapping,
com `get(Field<M, T>)` tipado. Ler uma coluna não seleccionada falha, em vez de
devolver `null`.

## 9. Paginação por cursor

`findManyCursor` usa keyset pagination:

- `take` é obrigatório (tamanho da página) e `skip` é proibido.
- Sem ordenação, a ordem é o identificador ascendente. Uma ordenação
  explícita tem de terminar no identificador, o que a torna total e
  determinística. Não pode repetir colunas nem usar colunas nullable.
- A página seguinte começa estritamente depois da última linha, através de
  `(a > ?) OR (a = ? AND b > ?) ...`, com o sentido de cada termo. Inserções
  ou remoções antes da posição actual não deslocam as páginas seguintes.
- `Cursor.encode()` produz um token Base64 URL-safe versionado, com a
  identidade da ordenação (tabela, colunas, sentidos) e os valores da chave,
  com etiquetas de tipo estáveis. `Cursor.decode` rejeita tokens malformados,
  demasiado longos, com bytes excedentes, UTF-8 inválido, tipos
  desconhecidos ou `NULL`. Rejeita-os com uma mensagem fixa e sem a causa
  original, para nunca ecoar conteúdo do cliente. Um token de outra
  ordenação, tabela ou tipo é rejeitado antes da execução.
- O token não é cifrado nem assinado. Os seus valores são sempre binds, por
  isso um token forjado só muda a posição. Um cursor não é um token de
  autorização: regras de acesso pertencem à condição da query.

## 10. Preview de SQL

`preview` devolve `RenderedSql` com o texto e a lista ordenada de binds, sem
abrir connection. `RenderedSql.toString()` mostra o texto e apenas o número de
binds, para que registar um preview não revele parâmetros. `Condition`,
`Cursor` e `Assignments` também não mostram valores em `toString()`.

## 11. Erros

- Uso inválido da API é rejeitado antes de qualquer connection, com
  `IllegalArgumentException` ou `NullPointerException`: paginação inválida,
  `in` vazio, cursor inválido, escrita vazia ou selecção incompatível.
- Falhas de base de dados continuam em `TuprelDatabaseException`, com fase,
  SQLState e código do driver (ADR-0009). Por exemplo, violações de unicidade
  (`23505`) e de foreign key (`23503`) mantêm o SQLState.
- Um `NULL` numa coluna declarada obrigatória, ou um enum desconhecido, falha
  na fase `MAPPING` com o nome da coluna e sem o valor.
- Um agregado que não devolva exactamente uma linha falha na fase `EXECUTION`.

## 12. Garantias mantidas

Não há lazy loading, dirty checking, identity map, sessão, cache nem
transacção implícita. Getters dos models gerados continuam a ser campos de
records. O mapping é código gerado sem reflection. Valores nunca entram no
texto SQL; identificadores vêm do schema validado, passam por
`SqlIdentifier` e são sempre citados.

## 13. Testes

- Golden files dos ficheiros gerados.
- Compilação real com `javac --release 21 -Xlint:all -Werror`.
- Testes de contrato que provam que uma condição de outro model e
  `contains` numa coluna UUID não compilam.
- Testes do fallback para nomes qualificados.
- Unit tests do modelo estrutural, do renderer (SQL e binds separados) e da
  API tipada.
- Fuzzing dirigido do descodificador de cursor.
- Integration tests com PostgreSQL 17.11 real via Testcontainers. Correm
  sobre código gerado pela CLI `tuprel generate` e cobrem CRUD, cada
  operador, semântica de `NULL`, padrões literais, paginação, projecções,
  cursor com empates e inserções concorrentes, restrições e injecção através
  da API gerada.

## 14. Adiado

Relações e `include` (RFC-003, Fase 5), transacções e locks (Fase 5),
`updateMany`/`deleteMany`/upsert e batch, agregações e group by, página
estruturada com totais, cursor para trás, streaming, `@map`/`@@map`, enums
nativos, `Json`, arrays e máscara configurável de valores sensíveis em
diagnóstico.
