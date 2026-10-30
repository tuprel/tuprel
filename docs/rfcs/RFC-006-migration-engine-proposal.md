# RFC-006 — Proposta incremental para o motor de migrations

Estado: **Proposta; aguarda aprovação explícita. Não autoriza implementação.**
Data: 2026-10-30. Relacionados: Fase 6, ADR-0008 (ainda proposto),
especificação canónica, secções 42–53, 143 e 174.

## Objectivo e fronteiras

Aplicar alterações revistas de forma ordenada, verificável e recuperável.
`deploy` aplica ficheiros existentes; nunca deriva DDL do schema actual.
Não criar módulos, tabelas de histórico ou comandos nesta entrega. O bootstrap
SQL do playground mantém-se como solução temporária, sem alegar ser migration.

Planner/diff puro recebe o schema validado e um modelo físico conhecido.
History/checksum e execução pertencem ao futuro `tuprel-migrate`.
SQL e catálogo PostgreSQL ficam num adapter explícito; CLI apenas orquestra.
Não reutilizar o carregador de `.env` dos testes como contrato de produção.

Antes de implementar, aprovar um ADR para a localização do adapter:

- Recomendação inicial: interfaces do migrator sem dependência PostgreSQL e
  implementação no módulo PostgreSQL existente, com composição pela CLI.
  Exige aprovar a nova aresta PostgreSQL → migrate; migrate não pode depender
  desse adapter, evitando ciclos.
- Alternativa: módulo `tuprel-migrate-postgresql` se a dimensão e consumidores
  justificarem um boundary próprio. Não criar esse módulo preventivamente.

## Formato, histórico e integridade propostos

Manter a estrutura da especificação: `tuprel/migrations/<id>_<nome>/migration.sql`.
IDs únicos e ordem total, validados; rejeitar colisões e ficheiros fora da root,
incluindo symlinks/path traversal. Formato e encoding precisam de versão.

Proposta: SQL UTF-8 com LF e SHA-256 dos bytes exactos, sem normalização
silenciosa. Configurar LF no Git quando o formato for aprovado. Alterar qualquer
byte de uma migration aplicada falha; resolver por nova migration, não reescrita.
Detectar também ficheiro removido, ID renomeado e ordem incoerente com o histórico.

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

Primeira fatia de execução limitada ao subconjunto transaccional suportado.
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

## Fatias propostas e critérios de aceitação

1. **Formato/checksum puro:** fixtures positivas e hostis, determinismo em
   Windows/Linux, alterações de bytes e confinamento de paths. Sem JDBC.
2. **History/status:** esquema/versionamento aprovados; base sem histórico,
   pending, applied, removed e checksum mismatch; status não escreve.
3. **Runner/lock transaccional:** aplicação ordenada, DDL + histórico atómicos,
   dois executores concorrentes, timeout, crash/religação e falha de commit.
4. **Diff/preview/safety:** golden tests de alterações suportadas, classificação
   destrutiva e desconhecidos. Sem executar planos não aprovados.
5. **Drift:** round-trip real entre schema, migrations e catálogo; adulteração
   de tabela/coluna/índice detectada, com limites explícitos.
6. **CLI dev/deploy/status:** exit codes documentados, redacção, sem geração em
   deploy e exemplos Windows/Linux compiláveis. Reset só em decisão posterior.

Cada fatia precisa de aprovação do seu contrato e testes PostgreSQL real local
e na CI. Nenhuma implementação começa antes da aprovação desta arquitectura
e dos ADRs pendentes acima.
