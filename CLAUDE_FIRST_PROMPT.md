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
8. lê `plans/MASTER_PLAN.md` e o plano da fase actual;
9. lê todos os ADRs já existentes que afectem essa fase.

Depois identifica a fase actual a partir do master plan e do estado real do
repositório. Apresenta um plano concreto apenas para o primeiro slice ainda não
concluído dessa fase; não avances para fases posteriores.

O plano deve indicar:

- ficheiros a criar ou alterar;
- contrato e critérios de aceitação do slice;
- módulos afectados e dependências permitidas;
- estratégia de testes e segurança aplicável;
- compatibilidade e documentação afectadas;
- riscos e decisões que precisam de ADR;
- comandos de validação que executarás no final.

Depois do meu aceite, implementa apenas esse slice em passos verificáveis. Não
faças `git push`, não publiques artefactos, não cries releases e não alteres
credenciais. Não escondas falhas de testes. Não reduzas a qualidade dos testes
apenas para obter verde no CI.

Quando terminares o slice, entrega um relatório com:

- o que foi criado;
- decisões tomadas;
- testes executados e resultados;
- dívida técnica deliberada;
- riscos ainda abertos;
- impacto nos critérios de saída da fase;
- próximo slice, sem o implementar.
