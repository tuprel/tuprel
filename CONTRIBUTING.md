# Contribuir para o Tuprel ORM

As contribuições para este projecto open source são feitas sob a [Apache License 2.0](LICENSE) (`Apache-2.0`).

## Fluxo

1. abre ou referencia uma issue/decisão;
2. identifica a fase e o módulo afectados;
3. lê os ADRs relevantes;
4. cria uma alteração pequena;
5. adiciona testes apropriados;
6. executa `./gradlew check` e testes de integração relevantes (comandos e lifecycle em `docs/development/BUILD_AND_TEST.md`);
7. actualiza documentação quando o contrato público muda;
8. submete PR com riscos, compatibilidade e evidência de testes.

## Branches e commits

Usa branches baseadas no trabalho realizado: `feat/`, `fix/`, `refactor/`,
`docs/`, `test/`, `chore/` ou `perf/`. O nome deve ser curto, descritivo, em
inglês e kebab-case, por exemplo `feat/f6-001-cli-migration-foundation`.
Não uses prefixos que identifiquem ferramentas, automações ou autores.
Esta convenção aplica-se a todas as fases e tarefas seguintes.

Usa commits pequenos e coesos no formato `type(scope): summary`, com resumo
em inglês e no imperativo. Tipos usuais: `feat`, `fix`, `refactor`, `docs`,
`test`, `chore`, `perf`, `build` e `ci`; o scope é opcional. Exemplo:
`docs(contributing): define branch and writing conventions`.
Preserva commits publicados e a atribuição verdadeira de autores e colaboradores;
não reescrevas histórico para alterar a apresentação.

## Escrita técnica e Pull Requests

Descreve o problema, a solução, os testes executados e as limitações reais.
Mantém títulos e descrições objectivos, sem promoção, repetições ou referências
irrelevantes ao processo de desenvolvimento. Comentários devem explicar decisões
ou restrições úteis. Não acrescentes assinaturas/créditos automáticos nem
afirmações não verificadas sobre autoria, testes, decisões ou desempenho.

## Alterações de arquitectura

Mudanças em módulos, dependências, formato do schema, API pública, modelo de migrações, compatibilidade de base de dados ou segurança exigem ADR.

## Dependências

Uma nova dependência deve justificar:

- por que é necessária;
- por que código próprio não é preferível;
- licença;
- manutenção/actividade do projecto;
- impacto transitivo;
- risco de supply chain;
- impacto de runtime e desempenho.

## Segurança

Vulnerabilidades não devem ser publicadas primeiro em issues públicas. Consulta `SECURITY.md`.
