# Política de Segurança

O Tuprel lida directamente com SQL, credenciais, migrações e dados persistentes. Vulnerabilidades podem ter impacto elevado.

## Reportar uma vulnerabilidade

Depois de estar activado o GitHub Private Vulnerability Reporting, usa **Report a vulnerability** na secção **Security** do repositório. Este canal ainda tem de ser activado após o primeiro push, antes de uma promoção pública ampla. Até estar disponível, não publiques detalhes exploráveis numa issue pública; contacta o mantenedor apenas por um canal privado já estabelecido.

## Áreas críticas

- SQL injection e escaping de identificadores;
- fuga de credenciais em logs/erros;
- bypass de safety checks em migrações;
- path traversal em CLI/codegen;
- geração de código malformado/injectável;
- deserialização insegura;
- execução arbitrária a partir de configuração;
- dependências comprometidas;
- concorrência em migrations;
- corrupção de schema history/checksums.

A baseline técnica está em `docs/security/SECURITY_REQUIREMENTS.md`.
