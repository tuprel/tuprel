# Regras de Testes

- Testa contratos públicos e invariantes importantes.
- Unit tests não substituem integration tests para comportamento PostgreSQL.
- Usa Testcontainers para testes que dependem de semântica real de PostgreSQL.
- Schema parser: testes positivos, negativos, recuperação de erro e golden fixtures.
- Codegen: golden files e compilação do código gerado.
- SQL: teste de SQL renderizado + parâmetros separados.
- Migrations: base vazia, upgrade incremental, drift, checksum, concorrência, falha, retry e mudanças destrutivas.
- Bugs corrigidos recebem teste de regressão.
- Testes devem ser determinísticos e independentes da ordem.
- Não uses sleeps fixos para sincronização quando existe mecanismo determinístico.
