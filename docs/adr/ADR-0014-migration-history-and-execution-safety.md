# ADR-0014 — Histórico confirmado e execução transaccional conservadora

Estado: **aceite como contrato arquitectural** em 2026-10-30. A aprovação
autoriza histórico/status read-only na F6-002B, mas **não autoriza executor**.
F6-003 exige revisão específica de SQL, preview, locks, falhas e confirmação.
Complementa ADR-0008, ainda proposto.

## Histórico (v1)

Uma tabela base `<schema_alvo>._tuprel_migrations`, sem RLS/views/regras
customizadas, com estrutura e versão verificadas antes de uso. O schema alvo
tem de existir e ser configurado explicitamente; status nunca o cria.

| Campo | Contrato proposto |
|---|---|
| `format_version` | smallint, obrigatório, exactamente 1 |
| `migration_id` | varchar(15), PK, ID canónico do catálogo |
| `migration_name` | varchar(64), nome canónico |
| `checksum` | char(64), SHA-256 lowercase |
| `state` | texto, exclusivamente `APPLIED` nesta versão |
| `started_at`, `finished_at` | timestamptz, obrigatórios, fim >= início |
| `execution_millis` | bigint >= 0, duração monotónica medida pelo executor |
| `engine_version` | varchar(64), versão identificável do tooling |

Validar tipos, constraints e versão; estrutura desconhecida ou permissões
insuficientes falham, sem tentar reparar. IDs aplicados têm de ser prefixo
ordenado do catálogo local. ID/nome/checksum alterado, ficheiro removido,
inserção retroactiva ou ordem incoerente bloqueiam deploy. Concorrência externa
maliciosa por DBA não está no modelo de segurança; requer ownership/ACLs da equipa.

Não persistir `RUNNING`/`FAILED` fora da transacção de DDL neste MVP. Isso cria
uma segunda máquina de recovery com janelas de crash. `APPLIED` identifica só
sucesso confirmado; tentativas são resultados do processo. Uma futura tabela
de tentativas necessita de ADR próprio, não de estados inventados a posteriori.

A F6-002B reconhece conservadoramente os tipos/constraints descritos no
[contrato da tarefa](../../plans/F6-002B_MIGRATION_STATUS.md). Nomes de CHECKs
podem variar; as definições devolvidas pelo deparser têm de ser conhecidas.
Não é prova semântica de equivalência de SQL arbitrário. O futuro writer deve
partilhar a definição canónica, a rever antes da F6-003; não existe bootstrap agora.

## Status read-only (F6-002B)

Uma transacção read-only, SELECTs bound e snapshot consistente. Sem tabela:
`UNINITIALISED`, 0 aplicadas e ficheiros pendentes; nenhuma escrita para bootstrap.
Tabela conhecida: `APPLIED`, `PENDING`, `REMOVED`, `MODIFIED`, `RENAMED`,
`OUT_OF_ORDER` ou incompatibilidade de formato/permissões. Nome não é identidade.
Pending é informação normal (exit 0); corrupção/incompatibilidade é exit 1/2
conforme contrato CLI. `DRIFT_NOT_CHECKED` é explícito até F6-005; não alegar
que checksums provam equivalência do catálogo físico. Nunca apresentar SQL ou
mensagem original do driver no diagnóstico normal.

## Lock e unidade transaccional (F6-003)

Uma connection dedicada durante todo o deploy; advisory lock **de sessão** antes
de verificar/criar histórico. Não usar connection de um pool em modo transaction
pooling. Chave: primeiros 64 bits big-endian de SHA-256 de um namespace binário
versionado + comprimento/UTF-8 do schema alvo. Mesmo schema/DB produz a mesma
chave entre versões do engine; colisão teórica apenas serializa targets distintos.
Locks são locais à DB. Nenhuma password, URL ou hashCode Java entra na chave.

Aquisição por `pg_try_advisory_lock` com prazo monotónico e polling limitado;
sem retry de SQL de migrations. Timeout configurável finito, default proposto
30 s; timeout de statement/DDL também finito e explícito. Se ocupado, diagnóstico
`LOCK_BUSY`, sem DDL. Libertar na mesma sessão em finally; falha na libertação
obriga abort/close, nunca devolver sessão com lock a um pool.

Histórico inicial, quando necessário, é criado numa transacção explícita sob
lock. Cada migration subsequente é uma transacção própria: DDL e INSERT de
`APPLIED` confirmam juntos. Uma migration falhada faz rollback e termina a
sequência. As anteriores já confirmadas permanecem aplicadas; não há promessa
de rollback global do deploy nem de recuperação universal de dados.

Falha ao confirmar, ligação perdida ou rollback falhado: `OUTCOME_UNKNOWN`,
terminar e abortar a connection. Não mudar autocommit para tentar recuperar,
não repetir SQL, não avançar. Novo operador/invocação reconcilia histórico
e catálogo numa nova ligação sob lock. Sem resposta inequívoca, intervenção
manual; não marcar sucesso, apagar histórico ou oferecer resolve automático.

## Contrato de SQL e aprovação

Ficheiro UTF-8 e checksum não autorizam execução. Recomendação para F6-003:
parser de uma **gramática DDL pequena e completa para o subconjunto suportado**,
com AST e renderer determinístico. Aceitar inicialmente CREATE TABLE simples,
ADD COLUMN nullable e CREATE INDEX normal, com tipos/expressões explicitamente
suportados e identificadores qualificados no target. FKs, defaults, expressões
e funções só entram quando a gramática e testes os cobrirem. Rejeitar qualquer
token/statement fora dessa gramática, transacções explícitas, SET/search_path,
DO/CALL, SQL de DML/backfill, funções, extensões e operações não transaccionais.
O parser desta gramática não é um parser PostgreSQL completo e não se apresenta
como tal. Nunca usar regex/split por `;` para declarar SQL arbitrário seguro.

O plano conservador classifica desconhecidos e operações destrutivas como
bloqueados. Preview devolve SQL, riscos e digest versionado do target lógico,
histórico observado e IDs/nomes/checksums. Aplicar exige aprovação explícita do
mesmo digest; mudar bytes ou estado invalida-a. Não existe `--yes` que ultrapasse
SQL desconhecido. A CLI não consegue provar revisão humana: o digest vincula
a confirmação técnica ao artefacto; revisão Git/CI continua responsabilidade
do processo de deployment. A especificação binária do digest/gramática é uma
subtarefa de F6-003 a rever antes de código executor, não API já disponível.

SQL manual avançado permanece visão do produto; precisa de parser apropriado
e ADR específico antes de ser executado. `transaction=false` e
`CREATE INDEX CONCURRENTLY` estão fora do MVP. A documentação PostgreSQL explica
a restrição transaccional e o risco de índice inválido em falha.

## Alternativas e testes de aprovação

- Lock de transacção: liberta a cada commit, não protege a sequência inteira.
- Linha RUNNING fora da transacção: diagnóstico melhor, recovery mais complexo;
  adiar até existir desenho de crashes entre estados.
- Executar SQL manual por confiança no developer: não impede COMMIT embutido,
  nem sustenta a garantia de atomicidade. Rejeitado para o primeiro executor.
- Regex para proibir palavras: não compreende comentários, strings e blocos;
  rejeitado. Um parser externo completo pode ser avaliado depois com custo,
  licença e suporte Windows/Linux/macOS documentados.

Testar PostgreSQL real: prefixo/histórico adulterado, status com role SELECT-only,
sem tabela, dois executores, lock ocupado, rollback de DDL+histórico, statement
falhado, perda de ligação/ack de commit e ausência de retry. Fixtures locais
usam schemas próprios em tuprel_devtest; não apagar DB ou dados do playground.

Fontes: [advisory locks PostgreSQL 17](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS),
[índices concorrentes](https://www.postgresql.org/docs/17/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY).
