---
name: plan-feature
description: Planeia uma funcionalidade Jorvia antes de qualquer implementação, incluindo arquitectura, segurança, testes, compatibilidade e critérios de aceitação.
disable-model-invocation: true
allowed-tools: Read Grep Glob
---

Planeia a funcionalidade indicada em `$ARGUMENTS`.

1. Lê o plano da fase actual, ADRs, arquitectura e regras relevantes.
2. Localiza código existente relacionado.
3. Define problema, âmbito e non-goals.
4. Define contrato público antes da implementação.
5. Identifica alterações por módulo e garante que o grafo de dependências continua válido.
6. Faz threat analysis da mudança.
7. Define testes unitários, integração, contract/golden e regressão necessários.
8. Identifica compatibilidade, performance, observabilidade e migração de utilizadores.
9. Lista decisões que exigem ADR.
10. Entrega um plano em passos pequenos, cada um verificável.

Não edites ficheiros nesta skill.
