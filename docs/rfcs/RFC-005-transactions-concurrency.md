# RFC-005: Transacções, concorrência e operações em massa

- **Estado:** Aceite para o subconjunto implementado na Fase 5
- **Data:** 2026-09-30
- **Âmbito:** transacções explícitas, savepoints, timeouts, row locks,
  streaming, batch, escrita condicional em massa e optimistic locking
- **Relacionados:** ADR-0009, ADR-0011, RFC-003, RFC-004

## 1. Transacções

`TuprelDatabase.transaction(options, work)` e o gerado
`TuprelClient.transaction(options, work)` fazem o seguinte:

1. Obtêm uma connection.
2. Aplicam `readOnly` e o isolamento pedido.
3. Desligam o autocommit.
4. Executam o trabalho com uma instância ligada a essa connection.
5. Fazem commit quando o trabalho retorna.

Qualquer excepção faz rollback e é relançada tal como foi lançada. Falhas de
rollback ficam em `suppressed`. Uma falha de commit é uma
`TuprelDatabaseException` na fase `TRANSACTION`, depois de rollback. Antes de
fechar, a connection volta a autocommit e ao isolamento e modo de acesso
originais, o que é importante com pools.

- **Isolamento:** `READ_COMMITTED`, `REPEATABLE_READ` ou `SERIALIZABLE`. Sem
  opção, usa-se o nível da connection.
- **Read-only:** escritas falham na base de dados (`25006`).
- **Prazo:** o timeout é um deadline da transacção inteira. Cada statement
  recebe como statement timeout o tempo restante. Um statement que começaria
  depois do prazo falha sem executar, com `SQLTimeoutException` como causa.
- **Confinamento:** o handle da transacção só é utilizável na thread que a
  começou e enquanto o trabalho corre. Usá-lo depois, ou noutra thread, lança
  `IllegalStateException`.
- **Sem retry:** falhas de serialização (`40001`) e deadlocks (`40P01`)
  chegam ao chamador com o SQLState. Repetir o trabalho é decisão da
  aplicação.

Nada inicia uma transacção implicitamente. Fora de `transaction`, cada
statement continua a usar uma connection própria em autocommit (ADR-0009).

## 2. Blocos aninhados

`transaction` chamado dentro de uma transacção abre um savepoint. Se o bloco
falhar, é feito rollback até ao savepoint e a excepção é relançada. A
transacção exterior só continua se o chamador tratar a excepção. Em PostgreSQL,
um erro fora de um savepoint aborta a transacção inteira; o savepoint é a forma
suportada de recuperar. Um bloco aninhado herda as opções exteriores e rejeita
opções diferentes, porque isolamento e modo de acesso não mudam a meio de uma
transacção.

## 3. Timeouts

`Query.timeout(Duration)` aplica-se a cada statement da operação, incluindo as
queries de relações incluídas. Usa `setQueryTimeout` do JDBC, com granularidade
de segundos: o valor é arredondado para cima. Dentro de uma transacção com
prazo, aplica-se o menor dos dois limites. Um statement cancelado falha com o
SQLState `57014`.

## 4. Row locks

`Query.lock(RowLock)` suporta `FOR UPDATE` e `FOR SHARE`, cada um com espera,
`NOWAIT` ou `SKIP LOCKED`. Aplica-se em `findMany`, `findFirst`,
`findById(id, query)`, `findManyCursor` e `stream`. Um lock fora de uma
transacção é rejeitado antes de qualquer connection, porque o autocommit o
libertaria de imediato. `count`, `exists` e as relações incluídas não bloqueiam
linhas. `NOWAIT` falha com `55P03` quando uma linha está bloqueada.

## 5. Streaming

`stream(query)` devolve `TuprelStream<M>`, `Iterable` de uma só passagem e
`AutoCloseable`, com statement e result set abertos até `close()`. O stream
exige uma transacção explícita. Assim, o cursor do servidor funciona com
fetch size (100 por omissão, ou `Query.fetchSize`) e a transacção fecha
qualquer stream deixado aberto quando termina. Usar o stream depois disso
falha. Streams não carregam relações.

## 6. Batch e escrita em massa

- **`createMany(inputs)`:** usa `INSERT` multi-linha. Fields ausentes numa
  linha usam `DEFAULT` da coluna, por isso linhas com fields diferentes
  partilham o mesmo statement. Até 30 000 binds por statement, um batch é um
  único statement e por isso atómico. Um batch maior precisa de vários
  statements e é rejeitado fora de uma transacção, para nunca ficar
  parcialmente gravado por acidente. Devolve o número de linhas inseridas.
- **`updateMany(where, input)`** e **`deleteMany(where)`:** exigem sempre uma
  condição tipada, porque não existe forma sem filtro, e devolvem o número de
  linhas afectadas. Um `@version` é incrementado em cada linha alterada.
- **Upsert:** fica adiado.

## 7. Optimistic locking

Um field `@version` (`Int` ou `Long` obrigatório, no máximo um por model; ver o
RFC-001) muda o contrato gerado:

- O input de update exclui a versão.
- `updateById(id, expectedVersion, input)` substitui a forma sem versão, que
  deixa de ser gerada.

O update é um único statement:
`UPDATE ... SET ..., "version" = "version" + 1 WHERE "id" = ? AND "version" = ? RETURNING ...`.

Se nenhuma linha mudar, um segundo statement distingue os dois casos:
- a linha não existe: o resultado é vazio;
- a linha existe com outra versão: lança `OptimisticLockException`, com a
  tabela e a versão esperada, sem o identificador.

Nada é escrito nesse caso. A versão inicial vem do `@default` ou do input de
criação. `deleteById` não verifica versão nesta fase.

## 8. Testes

- **Unit tests do lifecycle:** eventos JDBC em ordem para commit, rollback,
  falha de commit, savepoints, restauro de definições, handles escapados,
  outra thread, prazo e streams fechados pela transacção.
- **Integração em PostgreSQL 17.11:**
  - commit, rollback e savepoint parcial;
  - rollback por violação de foreign key;
  - semântica real de `REPEATABLE_READ` contra `READ_COMMITTED`;
  - read-only (`25006`);
  - locks entre duas transacções concorrentes: espera, `55P03`,
    `SKIP LOCKED`, `57014` por timeout de query e por prazo da transacção;
  - optimistic locking com conflito e com oito escritores concorrentes, dos
    quais exactamente um vence;
  - streaming de 250 linhas em lotes;
  - batch atómico com violação de unicidade;
  - escrita em massa.
