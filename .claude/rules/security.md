# Regras de Segurança

- Trata SQL generation, migrations, code generation e leitura de metadata como superfícies de ataque.
- Nunca exponhas secrets em logs, stack traces enriquecidos ou relatórios de diagnóstico.
- Testa inputs hostis para nomes, strings, unicode, delimitadores, comentários SQL e paths.
- Raw SQL seguro deve continuar parametrizado. Qualquer API realmente unsafe deve ter nome explícito, documentação e fricção suficiente para não ser usada por acidente.
- Operações destrutivas de migrations não podem ser silenciosas.
- Nunca alteres permissões de Claude Code para contornar um guardrail sem autorização do utilizador.
- Não desactives testes de segurança para resolver falhas.
- Dependências novas precisam de avaliação de licença, CVEs, manutenção e superfície transitiva.
