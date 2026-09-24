# ADR-0009: Fundação de execução JDBC e PostgreSQL

Estado: Aceite para o subconjunto da Fase 3

## Contexto

O schema e os tipos gerados da Fase 2 são descritivos. A Fase 3 precisa de
executar CRUD mínimo sem antecipar filtros, relações ou transacções. Os nomes
físicos de tabela e coluna ainda não são inferidos a partir de metadata gerada.

## Decisão

- `tuprel-sql` representa identificadores validados, valores tipados e as
  operações estruturais mínimas de insert, find by id, update by id e delete
  by id. Não recebe fragmentos SQL livres na API normal.
- `tuprel-postgresql` cita identificadores, transforma essas operações em
  texto SQL com placeholders e lista ordenada de binds, e escolhe os tipos
  JDBC para valores e nulls PostgreSQL. É o único renderer
  suportado nesta fase. Nomes físicos são fornecidos explicitamente pelo
  chamador, até existir um contrato de mapeamento gerado aprovado.
- `tuprel-runtime` recebe um `DataSource` da aplicação; nunca o fecha nem o
  configura. Cada chamada obtém e fecha a sua própria `Connection`. Cada
  `PreparedStatement` e `ResultSet` pertence à chamada e é fechado antes de
  ela terminar, incluindo nos percursos de erro. Nenhum recurso JDBC escapa
  através do row mapper. O chamador decide quando efectuar cada operação.
- A binding é fechada sobre tipos escalares suportados. `null` exige tipo SQL
  explícito; tipos desconhecidos falham antes de haver fallback textual.
  `Instant` usa UTC e `TIMESTAMPTZ`; `LocalDateTime` usa `TIMESTAMP`.
  `Json`, arrays e enums PostgreSQL não entram neste subconjunto.
- O mapper recebe apenas um `RowReader` com leituras tipadas e nulabilidade
  explícita. Uma falha JDBC conserva `SQLException` como causa e expõe fase,
  SQLState e código do driver; mensagens próprias não incluem SQL, URL nem
  valores de parâmetros.
- Cada chamada exige uma connection em autocommit, sem alterar o seu estado.
  Se o `DataSource` fornecer autocommit desactivado, a chamada falha e fecha
  essa connection. API de transacções, rollback e nesting
  ficam na Fase 5. A aplicação não deve usar este CRUD mínimo como substituto
  de uma transacção multi-operação.
- `TuprelDatabase` e renderer não guardam estado mutável por operação; podem
  ser partilhados se o `DataSource` fornecido suportar concorrência. Os
  recursos JDBC de cada chamada não são partilhados entre threads.

## Consequências

O CRUD da Fase 3 é uma API de fundação de baixo nível, ainda sem cliente
operacional gerado. Não há lazy loading, dirty checking, sessão implícita,
logging SQL ou execução escondida. A metadata da Fase 2 permanece compilável
sem runtime. Filtros, ordenação, paginação e API de query são da Fase 4;
relações e transacções são da Fase 5. O código de aplicação pode integrar um
mapper explícito com os valores Java gerados sem reflection.
