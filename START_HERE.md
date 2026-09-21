# Tuprel ORM - Agent Handoff

Este repositório contém o contexto de engenharia que Codex, Claude Code ou
outro agente autorizado deve ler antes de começar implementação do Tuprel ORM.

## Objectivo deste pacote

O pacote existe para evitar que um agente de programação comece a escrever código sem compreender o produto, a arquitectura, os limites de segurança, a estratégia de testes e a ordem de implementação.

O Tuprel deverá ser tratado como uma biblioteca de infraestrutura crítica. Um erro no ORM pode provocar perda de dados, corrupção de migrações, fugas de credenciais, SQL injection ou regressões silenciosas em aplicações de terceiros. Por isso, a implementação deve ser incremental, testada e auditável.

## Ordem de leitura obrigatória

1. `CLAUDE.md`
2. `docs/product/TUPREL_ORM_SPEC.md`
3. `docs/product/PRODUCT_PRINCIPLES.md`
4. `docs/architecture/ARCHITECTURE.md`
5. `docs/architecture/MODULE_BOUNDARIES.md`
6. `docs/security/THREAT_MODEL.md`
7. `docs/security/SECURITY_REQUIREMENTS.md`
8. `docs/testing/TEST_STRATEGY.md`
9. `plans/MASTER_PLAN.md`
10. o plano da fase actual
11. ADRs relacionados com a tarefa

## Como começar com um agente de desenvolvimento

Começa uma sessão na raiz do repositório, lê as instruções do agente e confirma
a fase actual em `plans/MASTER_PLAN.md`. Para Claude Code, o conteúdo de
`CLAUDE_FIRST_PROMPT.md` continua disponível como prompt de handoff.

Não peças a um agente para implementar o ORM inteiro de uma vez. Trabalha
apenas na fase actual e numa fatia verificável de cada vez.

## Regra de ouro

Nenhuma funcionalidade é considerada pronta porque compila. Uma funcionalidade só está pronta quando tem contrato documentado, testes adequados, tratamento de erros, avaliação de segurança e critérios de aceitação satisfeitos.

## Nome do projecto

Tuprel é o nome escolhido para o projecto. A pesquisa preliminar de disponibilidade permite o desenvolvimento público do repositório, sem constituir clearance legal ou de marca. Coordenadas Maven, direitos de domínio e gates de release continuam por resolver antes da publicação de artefactos. Consulta `docs/product/NAMING_AND_LEGAL.md`.
