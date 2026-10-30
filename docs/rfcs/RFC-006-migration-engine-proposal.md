# RFC-006 — Proposta incremental para o motor de migrations

Estado: **Desenho DB para aprovação. F6-001 integrada, ADR-0012 aceite;
F6-002A cria drafts offline. Histórico, adapter e execução continuam sem aprovação e sem código.**
Data: 2026-10-30. Relacionados: Fase 6, ADR-0008 (ainda proposto),
especificação canónica, secções 42–53, 143 e 174.

## Objectivo e fronteiras

Aplicar alterações revistas de forma ordenada, verificável e recuperável.
`deploy` aplica ficheiros existentes; nunca deriva DDL do schema actual.
Esta revisão transforma a proposta da consolidação da Fase 5 num contrato
incremental. F6-001 fornece a fundação offline; F6-002A acrescenta criação manual
de drafts em PR separado. O bootstrap
SQL do playground mantém-se temporário, sem alegar ser migration.

Planner/diff puro recebe o schema validado e um modelo físico conhecido.
Formato e checksums pertencem ao `tuprel-migrate` puro implementado nesta fatia;
history e execução continuam planeados nesse boundary, através de ports.
SQL e catálogo PostgreSQL ficam num adapter explícito; CLI apenas orquestra.
Não reutilizar o carregador de `.env` dos testes como contrato de produção.

Decisões formais para aprovação:

- [ADR-0012](../adr/ADR-0012-offline-cli-and-migration-files.md): módulo puro,
  formato v1 e CLI offline aceites no PR #26; criação F6-002A em revisão.
- [ADR-0013](../adr/ADR-0013-postgresql-migration-adapter.md): recomendação revista
  para adapter `tuprel-migrate-postgresql` separado, a criar em F6-002B; evita
  dependência de migrations no driver CRUD. Aprovar antes de JDBC.
- [ADR-0014](../adr/ADR-0014-migration-history-and-execution-safety.md): contrato
  concreto de histórico, estados, lock, SQL e recovery. Aprovar antes de DB.
- [CLI_EXPERIENCE](../product/CLI_EXPERIENCE.md): produto, flags, códigos,
  distribuição e comandos implementados versus roadmap.

## Revisão da base existente

Seis módulos revistos: schema (compiler/formatter/AST), codegen-java (geração e
ownership), sql (identificadores/modelo estrutural), runtime (JDBC de queries e
transacções), postgresql (renderer/binder/fixtures) e cli (três comandos).
Não existe parser SQL, leitor de catálogo ou executor de migrations reutilizável.
Não recriar schema parser/validator/codegen. Não transformar transacções CRUD
num executor de scripts nem ligar o loader de testes à configuração de produção.
`GeneratedSourceWriter.wouldChange` estende o guardrail existente para dry-run.

Incompatibilidades resolvidas no desenho: exemplos usam `data_hora_nome`, não
timestamp colado; SQL manual avançado da visão exige suporte posterior, não
autorização automática; status de F6-002 não promete drift antes de F6-005;
reset/transaction=false ficam fora do MVP. Os exit codes antigos são preservados,
mas a CLI deixa de imprimir linhas/valores de schema em erros para evitar secrets.

## Formato, histórico e integridade propostos

Manter a estrutura da especificação: `tuprel/migrations/<id>_<nome>/migration.sql`.
V1: `<id>` é `uuuuMMdd_HHmmss` UTC; ID único independentemente do nome.
Nome ASCII minúsculo de 1–64 caracteres, palavras separadas por `_` ou `-`.
Marker `.tuprel-migrations` obrigatório com `TUPREL-MIGRATIONS 1\n`.
Rejeitar colisões, nomes inválidos, entradas extra e links/path traversal.

Proposta implementada offline: SQL UTF-8 sem BOM/NUL, com LF final e SHA-256 dos bytes exactos, sem normalização
silenciosa. Este PR inclui a regra LF no Git para revisão do formato. Alterar qualquer
byte de uma migration aplicada falha; resolver por nova migration, não reescrita.
Snapshots são imutáveis e limitados a 1 MiB/script, 16 MiB/catálogo e 1000
migrations. F6-002 compara snapshots ao histórico para detectar remoção,
renomeação ou ordem incoerente; F6-001 não simula esse histórico.

Histórico contém ID/nome, checksum, início/fim, duração e estado; diagnóstico
seguro sem URL, binds ou mensagem original do driver. Localização, ownership e
versão da tabela interna são decisões a aprovar, não defaults já implementados.
Separar aplicação confirmada de tentativa falhada: DDL e registo de sucesso
devem confirmar na mesma transacção. Registo de tentativas fora dessa transacção
exige desenho de recovery para crashes entre esses dois estados.

## Concorrência e recuperação

Recomendação: advisory lock de sessão, na mesma connection durante toda a
sequência, pois um lock de transacção termina a cada commit. Chave determinística
e versionada por target lógico, timeout finito e erro explícito se ocupado.
Libertar em finally; se não for possível, abortar a connection antes do close.
Não prometer que um pool ou driver avariado pode sempre ser recuperado.
Esta escolha deriva da semântica dos
[advisory locks PostgreSQL](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS).

Uma migration transaccional confirma DDL e histórico atomicamente. Em falha,
rollback e diagnóstico; nenhuma continuação automática para o ficheiro seguinte.
Retry só depois de consultar o estado persistido, especialmente se a resposta
ao commit se perder. Nunca repetir SQL de forma cega após falha de connection.

## SQL manual e operações não transaccionais

O produto prevê SQL manual revisto, incluindo functions, backfills e operações
avançadas. Não deve ser tratado como input não confiável de utilizadores finais.
Mesmo assim, migrations que contenham controlo transaccional explícito podem
quebrar a atomicidade do runner. Aprovar o contrato e o mecanismo de validação
antes de as executar; uma regex simples não é um parser PostgreSQL suficiente.

Primeira fatia de execução exige um parser de gramática DDL explícita, AST e
rejeição completa de tokens/constructos não suportados (ADR-0014). Validar bytes
e hash não aprova SQL. Não executar scripts arbitrários por uma regex ou por
confiança implícita. Preview/digest aprovado vincula target, estado e artefactos;
SQL desconhecido/destrutivo continua bloqueado no MVP.
`transaction=false` fica planeado, mas exige estados de falha parcial e recovery
próprios. `CREATE INDEX CONCURRENTLY` tem restrições transaccionais e pode deixar
índices inválidos em falha, segundo a
[documentação PostgreSQL](https://www.postgresql.org/docs/17/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY).
Não o aceitar fingindo atomicidade. A limitação inicial deve ser explícita.

## Diff, drift e segurança

Diff conservador: distinguir adição, alteração potencialmente destrutiva e
desconhecido. Não inferir renames automaticamente. DROP, narrowing, NOT NULL
em tabelas com dados e mudanças com impacto de locks exigem revisão explícita.
Backups, deploy por etapas e restrições operacionais continuam responsabilidade
da aplicação; não há rollback universal seguro de dados.

Drift compara o catálogo real com o estado esperado, não apenas checksums de
ficheiros. É necessário um leitor mínimo do catálogo para o subconjunto da
Fase 6, sem afirmar que a introspecção completa da Fase 8 já existe. Objectos
não compreendidos produzem resultado desconhecido/requer revisão, não sucesso.
Identificadores passam por validação/quoting e valores de metadata por binding.

`status` é read-only e não cria tabelas por conveniência. `dev` pode gerar um
plano/SQL para revisão. `deploy` só executa artefactos existentes e aprovados.
`reset` e `resolve` ficam fora da primeira implementação; nenhum reset implícito.

## Fatias e critérios de aceitação

1. **F6-001 — CLI e ficheiros puros:** help/version/init, compatibilidade,
   dry-run e fixtures hostis, determinismo Windows/Linux, bytes/paths. Sem JDBC.
2. **F6-002 — Create offline e history/status:** duas subtarefas, sem misturar
   criação de ficheiros com DB; formato/adapter/histórico aprovados. Base sem histórico,
   pending, applied, removed e checksum mismatch; status não escreve.
3. **F6-003 — Runner/lock transaccional:** gramática/digest aprovados, deploy
   real apenas quando seguro; aplicação ordenada, DDL + histórico atómicos,
   dois executores concorrentes, timeout, crash/religação e falha de commit.
4. **F6-004 — Diff/preview/safety:** golden tests de alterações suportadas, classificação
   destrutiva e desconhecidos. Sem executar planos não aprovados.
5. **F6-005 — Drift/dev:** round-trip real entre schema, migrations e catálogo; adulteração
   de tabela/coluna/índice detectada, com limites explícitos.
6. **F6-006 — Consolidação:** documentação, end-to-end, compatibilidade e gates.
   Adopção do playground numa tarefa própria: baseline revisto contra o catálogo,
   preservação de dados e testes antes de substituir bootstrap. Nunca reset.

Cada fatia DB precisa de aprovação do contrato e testes PostgreSQL real local
e na CI. F6-001 e F6-002A estão autorizadas sem ligações ou DDL
novos. F6-002B/F6-003 ficam bloqueadas pelos ADRs pendentes; não interpretar a
implementação offline como aprovação tácita do executor.
