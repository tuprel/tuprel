# Tuprel ORM - Claude Code Handoff

Este repositório contém o contexto de engenharia que deve ser entregue ao Claude Code antes de começar a implementação do Tuprel ORM.

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

## Como começar com Claude Code

Começa uma sessão na raiz do repositório. Usa Plan Mode para a primeira análise. Depois cola o conteúdo de `CLAUDE_FIRST_PROMPT.md`.

Não peças ao Claude para implementar o ORM inteiro de uma vez. A primeira sessão deve tratar apenas da Fase 0.

## Regra de ouro

Nenhuma funcionalidade é considerada pronta porque compila. Uma funcionalidade só está pronta quando tem contrato documentado, testes adequados, tratamento de erros, avaliação de segurança e critérios de aceitação satisfeitos.

## Nome do projecto

`Tuprel ORM` é o nome de trabalho utilizado neste pacote. Antes de publicação pública, artefactos Maven, domínio, marca ou lançamento comercial, é obrigatória uma verificação de disponibilidade do nome. Consulta `docs/product/NAMING_AND_LEGAL.md`.
