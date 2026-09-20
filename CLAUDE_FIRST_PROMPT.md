# Primeiro prompt para Claude Code

Quero que trabalhes neste repositório como engenheiro principal responsável por construir o Tuprel ORM.

Antes de alterar qualquer ficheiro:

1. entra em Plan Mode;
2. lê `CLAUDE.md`;
3. lê `START_HERE.md`;
4. lê `docs/product/TUPREL_ORM_SPEC.md`;
5. lê `docs/architecture/ARCHITECTURE.md` e `docs/architecture/MODULE_BOUNDARIES.md`;
6. lê `docs/security/THREAT_MODEL.md` e `docs/security/SECURITY_REQUIREMENTS.md`;
7. lê `docs/testing/TEST_STRATEGY.md`;
8. lê `plans/MASTER_PLAN.md` e `plans/PHASE_0_FOUNDATION.md`;
9. lê todos os ADRs já existentes que afectem a Fase 0.

Depois apresenta um plano concreto apenas para a Fase 0. Não implementes ainda funcionalidades de ORM, parser, migrations, query DSL ou geração de código.

O plano deve indicar:

- ficheiros a criar ou alterar;
- estrutura Gradle multi-project proposta;
- módulos iniciais e dependências permitidas entre eles;
- versão de Java e toolchain;
- estratégia de qualidade, formatação e análise estática;
- estratégia de testes;
- configuração de CI;
- medidas de segurança de supply chain;
- riscos e decisões que precisam de ADR;
- comandos de validação que executarás no final.

Depois do meu aceite, implementa a Fase 0 em pequenos passos verificáveis. Não faças `git push`, não publiques artefactos, não cries releases e não alteres credenciais. Não escondas falhas de testes. Não reduzas a qualidade dos testes apenas para obter verde no CI.

Quando terminares a Fase 0, entrega um relatório com:

- o que foi criado;
- decisões tomadas;
- testes executados e resultados;
- dívida técnica deliberada;
- riscos ainda abertos;
- checklist dos critérios de saída da fase;
- proposta para a Fase 1, sem a implementar.
