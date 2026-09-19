# Jorvia ORM - Project Instructions

## Missão

Construir um ORM Java robusto, explícito, type-safe, seguro e simples de utilizar. O Jorvia deve reduzir boilerplate e fragmentação sem esconder o comportamento da base de dados.

## Antes de trabalhar

- Lê `START_HERE.md`, `plans/MASTER_PLAN.md` e o plano da fase actual.
- Lê os ADRs e regras relevantes antes de alterar arquitectura ou API pública.
- Para tarefas complexas, investiga primeiro e usa Plan Mode antes de editar.
- Não assumes que uma ideia no documento de visão é uma API final. Confirma o contrato no plano/ADR da fase.

## Âmbito inicial

- Java 21 é o baseline de linguagem e runtime.
- PostgreSQL é a primeira base de dados suportada.
- O core não depende de Spring, Jakarta Persistence, Hibernate ou outro ORM.
- Integrações com frameworks são módulos separados.
- O schema Jorvia é a fonte declarativa principal dos modelos.
- Código gerado nunca é editado manualmente.

## Princípios de produto

- Sem lazy loading invisível por omissão.
- Sem dirty checking escondido.
- Sem queries disparadas por getters.
- Operações com efeitos relevantes devem ser explícitas.
- Defaults devem ser seguros e previsíveis.
- SQL e planos de execução devem ser inspeccionáveis.
- Uma escape hatch de SQL raw deve existir, mas a API segura deve ser a opção normal.

## Segurança não negociável

- Valores provenientes do utilizador nunca são concatenados em SQL.
- Usa parâmetros vinculados/PreparedStatement para valores.
- Identificadores dinâmicos são gerados ou validados por allowlist e correctamente citados.
- Nunca registes passwords, tokens, URLs com credenciais ou parâmetros sensíveis por omissão.
- Nunca leias `.env`, chaves privadas ou credenciais para resolver uma tarefa.
- Migrações destrutivas devem ser detectadas e exigem consentimento explícito no fluxo de desenvolvimento.
- `migrate deploy` aplica migrações previamente revistas; não inventa alterações de schema em produção.
- Nunca uses dados reais de produção nos testes.

## API Java

- APIs públicas devem ser idiomáticas e fortemente tipadas.
- Nos exemplos públicos e documentação usa tipos Java explícitos, não `var`.
- Evita `Object`, casts e reflection como substituto de um modelo de tipos bem desenhado.
- Não introduzas Lombok no core sem ADR aprovado.
- Não exponhas classes internas de implementação como contrato público.
- Erros públicos devem usar uma hierarquia de excepções estável e documentada.

## Arquitectura

- Respeita o grafo de dependências em `docs/architecture/MODULE_BOUNDARIES.md`.
- Não cries dependências cíclicas.
- PostgreSQL-specific code fica fora do core agnóstico.
- O parser produz AST; validação semântica não deve ficar misturada com parsing.
- O query model/SQL AST deve ser independente do renderer PostgreSQL quando possível.
- Integrações Spring, Gradle e Maven não podem contaminar runtime/core.

## Desenvolvimento

- Implementa uma fatia pequena de cada vez.
- Não implementes fases futuras antecipadamente.
- Não crie abstracções sem necessidade demonstrada.
- Uma nova dependência externa precisa de justificação de segurança, manutenção, licença e peso.
- Corrige a causa raiz. Não hardcodes valores apenas para fazer um teste passar.
- Mantém alterações cirúrgicas e facilmente revistas.

## Testes

- Testa comportamento, não detalhes frágeis de implementação.
- Parser e validação: unit tests e golden tests.
- SQL rendering: golden tests + casos de escaping/binding.
- PostgreSQL: integration tests com PostgreSQL real via Testcontainers.
- Não uses H2 para afirmar compatibilidade PostgreSQL.
- Migrações: round-trip, drift, concorrência, checksums, falhas parciais e cenários destrutivos.
- Bugs corrigidos devem receber teste de regressão.
- Não removas nem enfraqueças testes para obter build verde.

## Qualidade e compatibilidade

- `./gradlew check` deve passar antes de considerar uma tarefa concluída.
- APIs públicas devem ter documentação e testes de contrato.
- Alterações incompatíveis exigem ADR e actualização de versão/roadmap.
- Warnings novos devem ser tratados, não ignorados silenciosamente.

## Git e releases

- Não faças `git push`, force push, reset destrutivo, publicação Maven ou release sem pedido explícito do utilizador.
- Não incluas segredos no repositório.
- Commits, quando pedidos, devem ser pequenos e semanticamente coesos.
- Não alteres histórico para esconder erros.

## Documentação

- Português europeu nos documentos internos deste repositório.
- Código, nomes de API, mensagens técnicas e JavaDoc podem usar inglês quando for a convenção pública escolhida.
- Exemplos Java devem compilar ou estar claramente marcados como pseudocódigo.
- Actualiza documentação e plano quando uma decisão muda o contrato.

## Definition of Done

Antes de declarar uma tarefa concluída, verifica `docs/development/DEFINITION_OF_DONE.md` e os critérios de saída da fase actual.
