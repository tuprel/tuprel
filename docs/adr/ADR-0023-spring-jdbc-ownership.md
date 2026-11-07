# ADR-0023 — Ownership JDBC numa transacção gerida por Spring

Estado **Proposto**. O P0-03 tem uma prova do comportamento actual. A
participação em transacções Spring continua por implementar.

## Contexto

O runtime Tuprel exige autocommit antes de abrir uma sessão que controla.
Um `DataSource` normal abre outra ligação quando Spring já tem uma transacção
em curso. A prova PostgreSQL do starter confirma que a transacção explícita
Tuprel pode confirmar de forma independente. Um
`TransactionAwareDataSourceProxy` encontra a ligação de Spring, mas o
runtime actual recusa-a por estar com autocommit desactivado.

## Proposta

- Manter o modo explícito existente. Introduzir um modo opt-in para operações
  por instrução, com uma interface JDK-only de empréstimo e devolução de
  ligação e ownership explícito. O adapter Spring resolve a ligação com
  `DataSourceUtils` para o `DataSource` configurado e prova que está
  associada à transacção actual antes de executar SQL.
- Spring controla commit, rollback, configuração JDBC e prazo. O runtime
  Tuprel controla apenas statements e result sets. A devolução usa o adapter
  e não encerra fisicamente uma ligação que Spring ainda controla. Recusar
  `transaction(...)`, savepoints Tuprel e acesso sem ligação comprovadamente
  associada nesse modo. Delimitar operações que podem escapar ao scope,
  incluindo streams, antes de disponibilizar a API.
- Começar com uma única fonte de dados JDBC e o transaction manager local.
  Validar propagação e a semântica de rollback antes de anunciar suporte.
  Manter os tipos Spring exclusivamente no starter. JTA, reactividade e
  transacções entre bases ficam fora do perfil inicial.

## Alternativas e consequências

O proxy transparente por si só não satisfaz o guardrail de autocommit.
Remover o guardrail global mudaria o contrato de todos os consumidores.
A alternativa explícita exige uma pequena extensão da aquisição JDBC,
mas preserva a fronteira de ownership e a API standalone.

## Validação para aceitar

Usar PostgreSQL real e uma aplicação externa para verificar commit e
rollback de operações Spring e Tuprel na mesma ligação, falha de aquisição,
ausência de ligação associada, read-only, propagação, prazo e rejeição de
transacções Tuprel aninhadas. Confirmar que nenhuma falha de statement
fecha, confirma ou devolve indevidamente a ligação do transaction manager.
