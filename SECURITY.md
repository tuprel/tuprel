# Política de Segurança

O Jorvia lida directamente com SQL, credenciais, migrações e dados persistentes. Vulnerabilidades podem ter impacto elevado.

## Reportar uma vulnerabilidade

Antes de o projecto ter um canal público de security advisories, não abras detalhes exploráveis numa issue pública. Contacta o mantenedor por um canal privado definido antes da primeira release pública.

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
