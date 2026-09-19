# Regras de Base de Dados e SQL

- PostgreSQL é o único dialecto obrigatório até a estratégia multi-database ser aprovada.
- Valores SQL devem ser enviados por binding/prepared statements.
- Nomes de tabela, coluna, schema, índice e enum são identificadores, não parâmetros de valores; devem passar por geração/validação e quoting correctos.
- Nunca formes SQL com valores externos através de concatenação ou interpolação.
- Queries devem possuir representação inspectável antes da execução.
- Sem lazy loading implícito.
- Relações são carregadas apenas quando pedidas.
- N+1 deve ser evitável e diagnosticável.
- Transacções, locks, timeouts e isolamento devem ter comportamento explícito.
- Não assumes sem testes que H2, SQLite ou outra base reproduz semântica PostgreSQL.
