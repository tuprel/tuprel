# F6-002B — Histórico e status read-only

Base: PR #27 integrado em 9a6b4f8; CI/CodeQL main aprovados. ADRs 0013 e
0014 aprovados explicitamente para arquitectura/histórico; executor não autorizado.

## Contrato e passos

1. Modelos puros em migrate: snapshot APPLIED e comparação de IDs/nomes/hashes,
   prefixo e cronologia. Estados podem acumular divergências. Sem SQL/JDBC.
2. Adapter migrate-postgresql → migrate/sql; DataSource do consumidor. Uma
   connection dedicada, autoCommit inicial obrigatório, REPEATABLE READ/READ ONLY
   confirmado no servidor. Rollback até no sucesso; restaurar apenas depois de
   rollback confirmado. Falha/close incerto nunca produz resultado nem retry.
3. Catálogos pg_catalog qualificados e parâmetros bound. Schema explícito existente,
   USAGE e SELECT necessários. Tabela ausente = UNINITIALISED, sem bootstrap.
   Estrutura desconhecida falha: tabela persistente normal, 9 colunas NOT NULL,
   tipos/tamanhos exactos, sem defaults/generated/identity/collations customizadas,
   PK não diferida válida em migration_id e quatro CHECKs validados (versão 1,
   state APPLIED, finished_at >= started_at, execution_millis >= 0).
   Comparar deparser PostgreSQL de forma exacta, sem fingir interpretar SQL
   arbitrário. Constraints semanticamente equivalentes mas desconhecidas falham.
   Rejeitar RLS, partições/herança, views, regras e triggers customizados.
   Validar também todos os valores e limitar histórico a 1000 linhas.
4. CLI: `migrate status --target-schema <identificador>`, catálogo local fixo,
   sem `--schema` (reservado a ficheiro), sem flags de escrita. Help sem I/O.
   Variáveis TUPREL_DATABASE_URL/USER/PASSWORD; nunca carregar .env. URL explícito
   sem credentials/opções arbitrárias; TLS verify-full obrigatório fora de loopback.
   Timeouts finitos. Driver PG 42.7.14 já verificado, runtime só na CLI.
5. Exit 0: normal, pending ou UNINITIALISED; 1: MODIFIED/REMOVED/RENAMED/
   OUT_OF_ORDER; 2: usage/configuração/permissões/estrutura/falhas JDBC. Sempre
   DRIFT_NOT_CHECKED. Sem mostrar URL/user/password/SQL/causas JDBC cruas.

## Segurança e testes

Reutilizar SqlIdentifier, catálogo v1 e fixtures PostgreSQL local/container.
Nenhum ciclo com o driver CRUD. DDL/DML só nas fixtures isoladas de testes.
Testar estados puros e em PG real, estrutura/constraints adulteradas, privilégios,
role SELECT-only, snapshot com escrita concorrente coordenada por barreira,
ausência de modificações e falhas de recursos/rollback sem retries. Unit tests
de configuração/redacção e help. Launchers reais e build raiz/playground em série.

Sem geração/aplicação de SQL, locks de deployment, histórico automático, reset,
drift físico ou alteração do bootstrap. Catálogo local não é atomicamente ligado
ao snapshot DB: excluir editores/criadores concorrentes. Superuser/DBA malicioso
fica fora do modelo. Estatísticas/logs operacionais do servidor podem mudar com
SELECTs; a garantia é ausência de escrita em schemas/tabelas/histórico da aplicação.

## F6-003 ainda bloqueada

Revisão específica de gramática SQL, digest/preview/target, aprovação, locking,
falhas/commit desconhecido e confirmação antes de implementar qualquer executor.
Definição canónica de constraints de histórico deverá ser partilhada com o futuro
writer e revista nessa tarefa; esta entrega só reconhece/consulta a estrutura.

## Melhoria futura

Evitar inclusão acidental de `.migrations.create.lock` no Git: desenhar criação
segura de uma regra ignore no init, com preview/ownership e compatibilidade com
.gitignore existente. Não editar configurações silenciosamente nesta tarefa.

## Revisão técnica e validação local

Separação confirmada: migrate continua java.base; adapter depende de migrate/sql
e JDBC; CLI compõe o driver PG 42.7.14 já verificado. Fixture PostgreSQL e
validação do alvo local movidas para test-support e reutilizadas, sem duplicar
o loader .env nem acrescentar dependências ao driver CRUD.

Achados corrigidos durante implementação:

- O runtime PG da CLI chega também aos classpaths de geração. A verificação
  estrita detectou locks incompletos; foram regenerados os locks afectados
  na CLI, no gerador de testes PostgreSQL e no playground, sem mudar versões.
- try-with-resources pode anexar mensagens JDBC de close como suppressed numa
  excepção segura. O adapter reconstrói a excepção pública sem causas/suppressed
  sensíveis; teste real de falha de close confirma a redacção.
- Rollback falhado obriga abort/close, sem repetir nem restaurar autoCommit.
  Não devolver resultado se rollback ou close falhar.

JDK 21/Windows, PostgreSQL 17.11 local, sem Docker. Resultados dos relatórios
da raiz/build-logic: **264 aprovados, 4 symlinks ignorados por privilégio Windows**.
Incluem 12 testes do adapter, 25 testes PostgreSQL anteriores e o launcher real.
Os novos testes unitários/CLI e os checks de JavaDoc Werror, Spotless e build-logic
passaram. A build incremental reutilizou tarefas de módulos sem mudanças.
Playground `build checkConnection`, executado depois da raiz: passou, com dois
testes aprovados. Verificação read-only confirmou 4 clientes, 8 produtos,
4 encomendas, 8 itens e zero schemas de integração restantes. Bootstrap intacto.

### Duas validações locais pendentes por autorização do utilizador

Não executar novamente enquanto o utilizador não disser **«Role criada, podes continuar»**:

1. `HistoryIntegrationTest.selectOnlyRoleWorksAndCannotWrite`
2. `HistoryIntegrationTest.missingUsageAndSelectPrivilegesAreExplicitErrors`

As primeiras tentativas falharam no setup porque a role não existe. Não foram
aprovadas nem convertidas em skips. O utilizador não está no computador e vai
rever/executar STATUS_TEST_ROLE.sql no pgAdmin posteriormente. Não alterar o
utilizador local nem tentar obter privilégios administrativos.

Os testes independentes foram seleccionados por `--tests` só no comando local;
nenhum filtro/exclusão foi introduzido na build/CI. A build local completa **não
está aprovada** até repetir os dois testes e `build` sem filtros. CI executa toda
a suite e cria a role apenas no PostgreSQL descartável que já utiliza; resultado
CI não substitui a confirmação local pendente. PR permanece draft, sem merge.

Depois da confirmação, da raiz:

```powershell
.\gradlew.bat :tuprel-migrate-postgresql:integrationTest --tests '*selectOnlyRoleWorksAndCannotWrite' --tests '*missingUsageAndSelectPrivilegesAreExplicitErrors' --dependency-verification strict -PtuprelTestDatabase=local
.\gradlew.bat build --dependency-verification strict -PtuprelTestDatabase=local
# Sempre depois da raiz:
.\gradlew.bat -p examples/tuprel-playground build checkConnection --dependency-verification strict
```

Limites restantes: macOS e TLS remoto não exercitados; TLS é validado por
propriedades/allowlist e requer configuração de certificados pelo consumidor.
Metadata SQL semanticamente equivalente mas desconhecida é recusada. DDL
administrativo concorrente no histórico e alterações simultâneas ao catálogo
local não são coordenados. Não há drift físico nem aprovação para execução.
