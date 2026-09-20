---
name: security-review
description: Executa uma revisão de segurança orientada a ORM, SQL, migrations, code generation, CLI e secrets.
allowed-tools: Read Grep Glob
---

Revê `$ARGUMENTS` contra `docs/security/THREAT_MODEL.md` e `docs/security/SECURITY_REQUIREMENTS.md`.

Procura especialmente:

- concatenação/interpolação de valores SQL;
- quoting incorrecto de identificadores;
- fuga de secrets;
- raw SQL inseguro;
- path traversal;
- geração de Java a partir de input não validado;
- migrations destrutivas silenciosas;
- races em migration locks;
- deserialização/configuração perigosa;
- dependências desnecessárias ou vulneráveis;
- logs com dados sensíveis.

Entrega vulnerabilidade, cenário, impacto, evidência e mitigação. Não corrijas automaticamente sem pedido.
