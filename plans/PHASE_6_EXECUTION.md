# Fase 6 — Plano de execução contínua

Plano operacional único. A autorização do utilizador permite implementação
sequencial e integração dos PRs abrangidos **apenas** depois de cumprir todos
os gates abaixo. Não autoriza operações em produção nem decisões críticas
ainda pendentes. Java 21, PostgreSQL primeiro, CONTRIBUTING.md obrigatório.

## Estado observado

Verificado em 2026-10-31: main em `9a6b4f872bc020634d9b8dee4ad6f56c2b5db938`,
CI/CodeQL aprovados. PR #28 aberto em draft, HEAD
`1cb315c9ef6b36ba83bcf106c006efe45a14fcf3`, quatro checks remotos aprovados.
Isso não aprova as duas validações locais de permissões ainda pendentes.

Estados: TODO, IN_PROGRESS, BLOCKED, TESTING, MERGED. TESTING inclui revisão e
checks em curso; BLOCKED identifica o gate externo que impede a integração.
Branch/PR/SHA só são preenchidos quando existem; não confundir código de um
PR com código integrado na main. Datas/HEADs acima são evidência da observação,
não substituem nova consulta antes de cada integração.

| Funcionalidade | Estado | Dependências | Branch / PR | Commit integrado | Problema pendente |
|---|---|---|---|---|---|
| F6-001 CLI e formato v1 | MERGED | ADR-0012 aceite | feat/f6-001-cli-migration-foundation / #26 | bf057d50c086811ae0602d46aa8db6d8d1026868 | — |
| F6-002A create offline | MERGED | F6-001 | feat/f6-002a-migration-create / #27 | 9a6b4f872bc020634d9b8dee4ad6f56c2b5db938 | — |
| F6-002B histórico/status | BLOCKED | ADRs 0013/0014 aceites; role local | feat/f6-002b-migration-status / #28 | — | Dois testes locais e build completa; manter draft |
| F6-003A1 gramática/AST/preview offline | TODO | Módulo/modelos de #28; aprovação da gramática antes de integrar contrato | — | — | Proposta específica a rever; não executar SQL |
| F6-003A2 plano, riscos e digest offline | TODO | A1; snapshot de histórico de #28 | — | — | Aprovação de serialização, target e segurança |
| F6-003B runner transaccional | BLOCKED | #28 e A1/A2 integrados; aprovação específica de execução | — | — | SQL, preview, locks, falhas e confirmação por aprovar |
| F6-003C CLI deploy | TODO | Runner aprovado/testado | — | — | Aprovação vinculada ao estado sob lock |
| F6-004 diff/preview | TODO | Deploy seguro; contrato de comparação | — | — | Modelo de diferenças/desconhecidos |
| F6-005 drift/dev | TODO | Diff e catálogo físico conhecido | — | — | Catálogo parcial, drift e confirmação revalidada |
| F6-006 consolidação | TODO | Subconjuntos anteriores integrados | — | — | Matriz, segurança, documentação e desempenho medido |
| F6-006 playground baseline | TODO | Motor estável; tarefa isolada com plano de preservação | — | — | Inventário read-only e consentimento antes de adoptar migrations |

## Critérios e testes por entrega

### F6-002B

Retomar somente após **«Role criada, podes continuar»**. Não criar roles com
outras credenciais, não alterar o utilizador existente, não ignorar testes nem
aprovar build local completa enquanto faltarem:

- HistoryIntegrationTest.selectOnlyRoleWorksAndCannotWrite;
- HistoryIntegrationTest.missingUsageAndSelectPrivilegesAreExplicitErrors.

Já verificado: 264 testes locais aprovados + 2 do playground; quatro skips de
symlink Windows executados/aprovados na CI Linux; 12 testes locais independentes
do histórico; 25 testes PG existentes; launcher real; JavaDoc/Spotless/build-logic.
CI completa aprovada em container isolado, incluindo privilégios. Não substitui
os dois casos locais. Primeiras tentativas falharam no setup por role inexistente.
Ver [evidência e comandos na branch do #28](https://github.com/tuprel/tuprel/blob/feat/f6-002b-migration-status/plans/F6-002B_MIGRATION_STATUS.md).

Depois da confirmação: executar os dois testes, build raiz sem filtros e,
sequencialmente, build/checkConnection do playground. Rever diff/segredos,
actualizar checks no HEAD actual, marcar ready, integrar se todos os gates
passarem e validar main antes de qualquer integração seguinte.

### F6-003A1 e A2

Divisão para evitar um PR grande e permitir revisão separada da gramática e
do vínculo de aprovação. Código offline para validar a proposta, não autorização
para executar DDL. Reutilizar MigrationFile/Id/Checksum, modelos de histórico e
SqlIdentifier. Não recriar parser de schema, loader dotenv ou validador de paths.

A1: gramática explícita do subconjunto, lexer/parser completo para esse
subconjunto, AST tipado e preview determinístico; toda a entrada consumida,
sem regex/split por ponto e vírgula como promessa de segurança SQL. Testes de
round-trip, bytes, comentários, limites, identificadores e entradas adversariais.
DDL PostgreSQL permanece no adapter, sem ciclos ou dependências externas novas.

A2: plano ordenado, riscos e desconhecidos bloqueados, verificação de target e
integridade, digest versionado de artefactos exactos e estado observado. Testes
golden independentes da plataforma, mutação de cada campo, determinismo e
invalidação por mudanças. Digest não prova revisão humana nem drift físico.
Não anunciar comandos ainda não implementados; não abrir ligações JDBC.

As propostas de gramática/digest/segurança exigem aprovação específica. Até lá,
os PRs correspondentes ficam draft/BLOCKED e não são integrados automaticamente.
Podem ser desenvolvidos/testados offline enquanto a role local bloqueia #28.

### F6-003B e C

Só após aprovação específica: connection dedicada, lock de sessão, histórico e
checksums, DDL + registo atómicos por migration, falhas de commit/rollback,
OUTCOME_UNKNOWN e ausência de retries perigosos. Testar concorrência, perda de
ligação, recuperação e recursos em schemas aleatórios da tuprel_devtest; CI
PG isolado. Deploy aplica apenas artefactos existentes revistos, nunca deriva
DDL do schema. Aprovação do plano revalidada sob lock antes de qualquer escrita.

### F6-004, F6-005 e F6-006

Diff determinístico com desconhecidos/perda de dados; drift físico com catálogo
explicitamente suportado; dev com confirmação vinculada e revalidação, sem
reset. Testar estados reais e adversariais. Consolidação inclui Windows/Linux,
macOS quando houver runner, segurança, locks/falhas, integridade, exemplos e
desempenho medido sem inventar benchmarks. Bootstrap do playground só muda em
tarefa própria; não apagar/modificar os dados persistidos para demonstrar o motor.

## Gates de integração e disciplina Git

1. Consultar PR, HEAD, base, checks/reviews e ruleset reais. PR ready e sem conflitos.
2. CI obrigatória, CodeQL e Dependency Review aprovados **nesse HEAD**; nenhum
   blocker de review, skip injustificado, teste desactivado ou gate enfraquecido.
3. Testes locais obrigatórios aprovados; novos contratos críticos aprovados.
4. Revisão técnica/segurança do diff, auditoria de segredos/commits e confirmação
   de que não houve alteração de dados reais fora dos schemas de teste.
5. Respeitar integralmente a protecção da main. Native auto-merge observado como
   indisponível (`allow_auto_merge=false`); não alterar essa configuração. Usar
   merge explícito condicionado ao HEAD depois de verificar todos os critérios.
6. Verificar CI/CodeQL no novo HEAD da main. Falha suspende integrações; corrigir
   regressão antes de continuar. Actualizar branches por merge normal, sem force
   push/rebase destrutivo, preservando commits/autoria.

PRs empilhados podem usar a branch da entrega anterior como base para reutilizar
modelos sem duplicar alterações. CI, CodeQL e Dependency Review devem executar
também em bases feat/**, com os mesmos testes/gates. Retarget para main depois
de integrar a dependência, comparar o diff e repetir os checks do HEAD actual.
Não integrar uma dependência bloqueada só para desbloquear o PR seguinte.

Actualizar este plano após cada entrega com evidências reais. Uma pendência
administrativa não bloqueia trabalho offline independente; decisões novas de
execução continuam a exigir aprovação. Não publicar releases nem operar produção.
