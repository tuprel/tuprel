# RFC-003: Relações e carregamento explícito

- **Estado:** Aceite para o subconjunto implementado na Fase 5
- **Data:** 2026-09-30
- **Âmbito:** emparelhamento de relações no schema, componentes de relação nos
  valores gerados, `include` e escrita de relações
- **Relacionados:** RFC-001, RFC-002, RFC-004, RFC-005, ADR-0005, ADR-0011

## 1. Fronteira da decisão

Uma relação nunca é carregada implicitamente. Só é lida quando uma query a
inclui, e cada relação incluída custa uma query por nível para todas as linhas,
nunca uma query por linha. Uma relação não carregada falha quando lida; não
dispara I/O.

Entram 1:1, 1:N e N:N através de um model de junção explícito, com `include`
aninhado. Ficam fora a relação N:N implícita, filtros sobre relações
(`some`/`every`), escrita aninhada de relações, acções referenciais e nomes de
relação.

## 2. Emparelhamento no schema

O `RelationGraph` de `tuprel-schema` resolve cada field de relação:

- **Lado dono:** um field singular com `@relation(fields, references)`. As
  colunas de chave estrangeira estão no seu model.
- **Lado inverso:** um field de relação sem `@relation`, lista ou singular. Tem
  de corresponder a exactamente um field dono no model alvo que referencie o
  seu próprio model. Sem nomes de relação, zero candidatos
  (`TUPREL-SCHEMA-SEM-021`) ou vários candidatos (`TUPREL-SCHEMA-SEM-022`) são
  erros. Dois inversos que emparelhariam com o mesmo dono também são
  `SEM-022`.
- **Inverso singular (1:1):** tem de ser opcional (`SEM-023`), e as colunas do
  dono têm de ser únicas: `@id`, `@unique` ou um `@@unique` exacto
  (`SEM-024`).
- Um lado dono sem inverso continua válido. Auto-relações são suportadas.

Isto torna a validação da Fase 1 mais estrita: uma relação inversa sem par
passa a ser um erro, em vez de metadata sem significado.

## 3. N:N

Uma relação N:N declara-se com um model de junção e duas relações donas. É o
que a especificação recomenda quando a relação tem dados próprios:

```tuprel
model NoteTag {
    id Long @id
    noteId Long
    tagId Long
    note Note @relation(fields: [noteId], references: [id])
    tag Tag @relation(fields: [tagId], references: [id])

    @@unique([noteId, tagId])
}
```

A navegação é um `include` aninhado:
`NoteInclude.tags(tags -> tags.include(NoteTagInclude.tag()))`.

A forma implícita (`tags Tag[]` e `notes Note[]` sem model de junção) fica
adiada, porque exige uma convenção de nome e criação da tabela intermédia que
pertence ao motor de migrations. A chave composta `@@id` do model de junção
continua adiada pelo RFC-001: usa-se um `@id` próprio e um `@@unique`.

## 4. Valores gerados

Um model com relações ganha, depois dos componentes escalares, um componente
`TuprelRelation<V>` por relação, pela ordem do schema:

| Relação | `V` |
|---|---|
| lista (1:N, lado inverso) | `List<Target>` |
| dono obrigatório | `Target` |
| dono opcional ou inverso singular (1:1) | `Optional<Target>` |

`TuprelRelation` é gerado no package raiz e continua JDK-only.
`isLoaded()` indica o estado. `get()` devolve o valor ou falha com
`IllegalStateException` e a mensagem
`User.posts was not loaded. Add include(UserInclude.posts()) to the query.`

O record mantém um construtor só com os escalares, que cria as relações como
não carregadas. Assim, o código que já construía valores continua a compilar.
A igualdade inclui o estado das relações. `toString()` mostra o valor
carregado ou `not loaded`.

## 5. Include

Para cada model com cliente, `P.include.UserInclude` expõe duas formas por
relação cujo alvo também tem cliente:

- `posts()`
- `posts(UnaryOperator<Query<Post>>)`

`Query<User>.include(...)` só aceita `Include<User>`. Um include de outro
model não compila.

A query aninhada de uma relação to-many aceita condição, ordenação e includes
aninhados. Sem ordenação, as linhas relacionadas vêm por identificador
ascendente. Uma relação to-one aceita apenas includes aninhados. Paginação,
cursor, locks, timeout e fetch size aninhados são rejeitados. Incluir a mesma
relação duas vezes é rejeitado.

O `include` funciona em `findMany`, `findFirst`, `findById(id, query)` e
`findManyCursor`. É rejeitado por `select`, `count`, `exists` e `stream`.
`create` e `updateById` devolvem a linha com relações não carregadas.

## 6. Carregamento

O `RelationLoader` de `dev.tuprel.runtime.query` carrega assim cada relação
incluída:

1. Recolhe as chaves distintas das linhas de origem. Chaves com uma coluna
   `NULL` não pedem nada e dão uma lista vazia ou `Optional.empty()`.
2. Executa `SELECT` das colunas do alvo com `WHERE chave IN (...)`, ou uma
   disjunção de igualdades para chaves compostas, em lotes de 500 chaves.
3. Carrega recursivamente os includes aninhados sobre todas as linhas
   carregadas.
4. Agrupa por chave, com decimais comparados numericamente, e anexa uma cópia
   da linha de origem com a relação carregada.

Uma relação to-one obrigatória sem linha relacionada, ou um 1:1 com mais do que
uma, falha com `IllegalStateException`. Todas as queries usam a mesma
transacção quando existe uma, e o mesmo timeout da operação. O número de
statements de uma operação é `1 + Σ ⌈chaves / 500⌉` por relação e nível, e os
testes de integração verificam-no.

## 7. Escrita de relações

Relações escrevem-se pelas colunas de chave estrangeira, que são fields
escalares comuns dos inputs gerados (`NoteCreate.customerId(...)`). Os inputs
não têm fields de relação. Um valor com relações carregadas nunca é gravado
como grafo: não há cascata, sincronização de colecções nem persistência
implícita de linhas relacionadas. Apagar uma linha só afecta outras através das
foreign keys da base de dados. Escrita aninhada (`create` com `connect`) fica
adiada.

## 8. Testes

- Unit tests do `RelationGraph`: pares válidos e cada diagnóstico novo.
- Golden files do record e dos includes, e compilação real.
- Contrato de compilação: um include de outro model não compila.
- Unit tests das regras de include.
- Integração em PostgreSQL 17.11: 1:1, 1:N, N:N aninhado, filtros e ordenação
  aninhados, `Optional` vazio e mensagem de relação não carregada. Inclui
  contagem exacta de statements com 3 clientes e com 1 200 clientes, para
  provar que não há N+1.
