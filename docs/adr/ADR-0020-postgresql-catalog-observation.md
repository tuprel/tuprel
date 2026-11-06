# ADR-0020 — PostgreSQL Catalog Observation

Estado **aceite** para F6-005A e F6-005B. A aceitação abrange exclusivamente
observação de catálogo e avaliação de drift em modo read-only.
Base PostgreSQL 17 e Java 21. A publicação de artefactos e a eventual revisão
do protocolo de deploy são tratadas no [ADR-0021](ADR-0021-safe-migrate-dev.md).

## Contexto

O diff do ADR-0018 compara dois estados lógicos. O runner do ADR-0017 verifica
precondições das operações concretas. Para determinar drift é necessário
observar as propriedades físicas admitidas, conhecer a cobertura da leitura e
comparar o resultado com o replay da história aplicada.

## Decisão

### Alvo, permissões e sessão

- Um database, um schema explícito e um servidor PostgreSQL 17 primário.
  Reutilizar `MigrationDatabaseSettings`, `SqlIdentifier` e o fingerprint do
  ADR-0016. Confirmar versão do servidor, ausência de recovery, URL JDBC
  efectiva validada, `current_database()`, `CURRENT_USER`, `SESSION_USER` e
  política TLS. Recusar alias, role switching e schema ausente ou ambíguo.
- Resolver database e namespace por igualdade exacta em `pg_database` e
  `pg_namespace`. Capturar os OIDs como evidência de identidade neste alvo.
  Recusar schemas de sistema, temporários, TOAST e nomes reservados pelo
  motor. O endpoint e o TLS identificam o serviço configurado. Os OIDs têm
  alcance local e podem ser reutilizados. Uma substituição de cluster por
  trás do mesmo endpoint exige nova validação operacional, mesmo que os nomes
  e OIDs coincidam. A primeira versão pressupõe um endpoint primário estável
  sob controlo do operador durante a janela de revalidação.
- Permissões mínimas de observação são `CONNECT`, `USAGE` no schema e `SELECT`
  nos catálogos consultados. Verificar `USAGE` explicitamente. Quando o acesso
  padrão aos catálogos tiver sido revogado, conceder apenas a leitura dos
  catálogos necessários por processo administrativo separado. Para F6-005B
  acrescenta-se `SELECT` na tabela de histórico quando ela existe. `CREATE`,
  ownership de tabelas, superuser, `pg_read_all_data` e acesso a linhas de
  negócio ficam fora do perfil do observador. O cliente recusa acesso
  insuficiente sem tentar elevar privilégios ou substituir a fonte por vistas
  que filtrem objectos invisíveis.
- Abrir uma ligação física dedicada e idle. Usar uma transacção `REPEATABLE
  READ READ ONLY`, confirmar ambos os modos no servidor antes do inventário e
  terminar por rollback e fecho. Partilhar a validação de identidade da sessão
  com o runner, distinguindo o modo esperado. O `matches` actual exige
  read/write, pelo que o observador precisa da variante read-only. Falha de
  limpeza invalida o resultado. Descartar ligação de estado incerto conforme
  ADR-0017.
- Usar `SET LOCAL search_path = pg_catalog` e qualificar catálogos, tipos,
  funções e operadores críticos. Os filtros recebem nomes/OIDs por binding.
  O inventário usa consultas SQL fixas a tuplos de catálogo no mesmo snapshot.
  Ler flags de existência sem obter texto de expressões, defaults, predicates,
  funções ou definições descompiladas. A operação fica limitada a leitura,
  configuração local à transacção e encerramento da sessão. Schema, histórico,
  dados e configuração persistente ficam intactos.

### Inventário e cobertura v1

Começar por todas as relações com `relnamespace` igual ao OID verificado.
Verificar também índices, constraints e herança associados por OID, incluindo
referências a partir de outro schema. Cada linha encontrada tem de ser
representada, classificada como derivação conhecida ou diagnosticada. Um filtro
que devolve apenas tabelas suportadas poderia ocultar drift.

| Superfície | Representar ou verificar | Condições que impedem cobertura completa |
|---|---|---|
| Relações, `pg_class`, `pg_am`, `pg_inherits`, `pg_depend` | Tabela heap ordinária e permanente, sem partições, herança ou typed table. Índices no mesmo namespace da tabela. Verificar método de acesso, opções e tablespace implícito | Views, materialized views, foreign tables, sequências, partições, unlogged, herança em ambos os sentidos, opções ou tablespace explícitos, objectos geridos por extensão e tipos de relação desconhecidos |
| Colunas, `pg_attribute`, `pg_type`, `pg_attrdef` | Nome exacto, nullability, tipo base `pg_catalog` por OID e namespace. Apenas `uuid`, `bool`, `int2`, `int4`, `int8`, `text`, `date` e `timestamptz`. `atttypmod = -1`, sem dimensões e com collation do tipo | Defaults, valores missing, identity, generated, colunas dropped, typmods, arrays, domínios, enum, tipos de utilizador, collation ou opções de coluna que excedem o perfil |
| Constraints e efeitos, `pg_constraint`, `pg_trigger`, `pg_rewrite`, `pg_policy` | Zero ou uma PK. Se presente, uma coluna conhecida, `NOT NULL`, validada, imediata e não deferrable. Vincular o seu índice por `conindid`. Nome da PK e do índice implícito ficam fora da igualdade | FK de entrada ou saída, check, unique extra, exclusion, PK composta, constraint não validada ou não reconhecida. Triggers de utilizador, rules, RLS/policies e replica identity fora do padrão |
| Índices, `pg_index`, `pg_class`, `pg_am`, `pg_opclass`, `pg_opfamily` | Btree válido, pronto e live. Nome exacto dos índices explícitos e ordem exacta das chaves. `indnatts = indnkeyatts`, atributos positivos distintos e opções ASC/NULLS LAST padrão. Confirmar collation de cada coluna e opclass/opfamily nativas aplicáveis ao tipo, pelo OID e namespace | Expressões, predicates, INCLUDE, unique excepto índice da PK validada, opclasses customizadas, collation/sort não padrão, índice inválido/em construção, associação ambígua ou dependências não reconhecidas |
| Objectos derivados e reservados | Reconhecer row type/array implícitos e TOAST interno associado por OID. Verificar a forma da tabela de histórico e a associação do seu único índice de PK antes de os separar do inventário de aplicação | Nome reservado `_tuprel_*` fora da allowlist interna, forma de histórico incompatível, índice extra no histórico ou objecto derivado sem ligação válida. Tipos de utilizador independentes no namespace são diagnosticados em `pg_type` |

Nomes observados passam pelo `SqlIdentifier` existente, sem truncar ou
normalizar. Uma referência em falta ou incoerente invalida a leitura. Uma
propriedade presente e compreendida como fora do perfil torna-a incompleta.
O perfil compara nomes case-sensitive, tipos, nullability, PK e índices
explícitos. Ordinais físicos de colunas, nomes internos de PK/TOAST, linhas,
estatísticas, ACLs, owners, funções independentes e objectos fora do namespace
sem associação descrita na tabela ficam fora da igualdade. Ficam também fora
os event triggers globais, que continuam a ser um gate separado do runner.
Uma tabela sem RLS e sem policies pode ter ACLs diferentes da expectativa e
continuar equivalente neste perfil.

### Snapshot, concorrência, limites e erros

Em F6-005A o snapshot resulta apenas de consultas de catálogo. `REPEATABLE
READ` permite observar o estado committed visível no início do snapshot. DDL
committed depois pode ficar fora da leitura. O resultado representa esse
instante, com uma versão do perfil de cobertura, sem reserva do estado futuro.
Inconsistência de OID, relação que desaparece durante acesso necessário,
timeout, deadlock, cancelamento, perda de ligação ou erro de permissões implica
`UNKNOWN`. Nunca combinar linhas de transacções distintas. Em F6-005B a
história e o catálogo devem partilhar a mesma ligação e o mesmo snapshot.

A classificação do histórico em F6-005A verifica colunas, tipos, flags,
constraints por categoria/contagem e índice, sem ler linhas ou descompilar
expressões. `COMPLETE` certifica a cobertura das relações de aplicação. A
validação do conteúdo e das expressões canónicas do histórico cabe à F6-005B.

A leitura de linhas do histórico exige a janela sem DDL externo descrita no
ADR-0021. Antes de consultar o histórico por nome, validar estrutura e OID,
obter `ACCESS SHARE` apenas na relação validada e confirmar o lock no OID
esperado. Usar o reader conn-scoped e validar o índice canónico, sem criar
histórico. Restringir o reader existente, que descompila as checks internas,
a histórico de proveniência administrativa comprovada sob esse controlo. Não
descompilar expressões de relações arbitrárias ou de histórico sem confiança.
Em particular, uma verificação MVCC antiga seguida de SELECT por
nome sobre uma possível relação substituída não é aceite. Se não for possível
estabelecer a janela de controlo, a observação de catálogo continua disponível
e o drift dependente de histórico fica `UNKNOWN`.

Fixar limites da primeira versão. Usar 256 colunas por tabela, 32 chaves por
índice, 4 096 statements equivalentes no replay, 16 384 objectos representados
e 1 000 linhas de histórico. Impor 65 536 linhas de metadados no total e 16 MiB
de dados decodificados antes de construir o snapshot. Cada consulta limitada
detecta excedente com limite + 1. Excedente produz `UNKNOWN`, sem truncagem
que possa parecer cobertura completa. Usar queries por lote, não por coluna.
Propor 5 s para ligação/login, `lock_timeout` de 2 s, `statement_timeout` e
timeout JDBC de 5 s por consulta, `transaction_timeout` de 20 s no PG17 e
socket timeout de 30 s. Limitar a leitura e fechar mesmo quando há falha.
Um prazo de servidor não garante um limite igual para limpeza da ligação.

Diagnósticos normais mostram estado, categoria estável e contagens limitadas.
Relatórios explícitos podem identificar objectos representáveis por nomes
validados do alvo. URLs, credenciais, SQL fonte, texto de expressões, linhas
de negócio e mensagens, causas ou suppressed do driver ficam fora do
resultado. `toString` apresenta apenas resumo redigido. Sem retries internos
que misturem instantes ou escondam uma perda de ligação.

### API e Physical Drift Detection

Contrato proposto, com nomes indicativos para revisão. `PostgresqlCatalogObserver`
usa a configuração validada. `observe(SqlIdentifier)` devolve um
`CatalogObservation` imutável com estado, versão de cobertura,
`Optional<SchemaSnapshot>` e diagnósticos tipados. A identidade observada fica
encapsulada no adapter. `PostgresqlDriftInspector.inspect(SqlIdentifier, Path)`
obtém evidência do catálogo local, história e alvo vivo e devolve
`PhysicalDriftReport`. O chamador indica o schema e a raiz do catálogo v1.
Nenhuma destas APIs recebe uma leitura fornecida pelo chamador como prova do alvo.

| Estado de observação | Resultado |
|---|---|
| `COMPLETE` | Inventário exaustivo dentro do perfil e sessão encerrada com sucesso. Único estado com `SchemaSnapshot` disponível |
| `INCOMPLETE` | Leitura fiável detectou propriedade/objecto fora do perfil. Apenas diagnósticos, sem snapshot comparável |
| `UNKNOWN` | Alvo, leitura, orçamento ou limpeza sem evidência fiável. Apenas diagnósticos |

Reutilizar o AST e `SchemaSnapshot.replay` para materializar o estado completo.
Reutilizar apenas a comparação estrutural de `SchemaDiff` para relatar
diferenças físicas. O seu preview e os seus avisos de diff offline continuam
reservados a propostas lógicas. O resultado de drift expõe estado, cobertura,
diferenças estruturadas quando confirmadas e diagnósticos seguros.

O estado esperado vem dos ficheiros v1 cujo ID, nome, bytes/checksum e ordem
coincidem com o prefixo `APPLIED` observado no mesmo alvo. Usar
`MigrationDirectory`, `MigrationStatus`, parser e replay existentes. Ficheiros
pendentes ficam fora da referência. Historial incompatível, divergente ou
indisponível, SQL aplicado fora da gramática e replay impossível dão `UNKNOWN`.
Com histórico ausente, aceitar referência inicial vazia apenas se o inventário
completo estiver vazio e o alvo tiver proveniência de aprovisionamento inicial
confirmada pelo operador. Os ficheiros locais são todos pendentes neste caso.
Isto permite o primeiro deploy após publicar o primeiro candidato. O vazio
observado sozinho não comprova essa proveniência. Histórico ausente sem esta
evidência produz `UNKNOWN` e impede adopção ou reparação implícita.

| Drift | Evidência exigida |
|---|---|
| `NO_DRIFT` | Referência válida e observação `COMPLETE` equivalentes no perfil explicitado |
| `DRIFT_DETECTED` | Ambas as representações válidas e completas, com diferenças confirmadas por nome, tipo, nullability, PK ou índices |
| `UNKNOWN` | Referência não comprovada, observação incompleta ou desconhecida. Tem precedência sobre diferenças parciais |

Remoções e adições são reportadas separadamente. Um relatório de drift não
infere renames nem gera DDL correctivo. Antes de futura escrita, obter nova
evidência no alvo vivo, sob o protocolo de exclusão e revalidação do ADR-0021.

## Alternativas e consequências

- `information_schema` e `DatabaseMetaData` são convenientes para portabilidade.
  A visibilidade filtrada e a cobertura de índices e flags não chegam para
  demonstrar completude. A escolha de `pg_catalog` torna a versão PostgreSQL
  explícita e exige testes ao subir de versão major.
- Queries independentes em `READ COMMITTED` seriam simples, mas podem misturar
  gerações de catálogo. Bloquear todas as relações de aplicação para observar
  exigiria mais privilégios e aumentaria a contenção. Escolhemos um snapshot
  temporal sem locks de aplicação no observador de catálogo.
- Ignorar propriedades desconhecidas aumentaria o número de schemas aceites.
  Recusar cobertura completa reduz a aplicabilidade inicial e evita certificar
  equivalência por omissão. A execução actual conserva o contrato até existir
  aprovação e implementação separadas do novo protocolo.

## Critérios de aceitação e sequência

F6-005A deve provar leitura de catálogo em PostgreSQL 17 real com role de
privilégio mínimo e sem SELECT em tabelas de negócio. Testar os oito tipos,
nomes e case, PK/índices, objectos extras e cada classe fora do perfil. Usar
fixtures adversariais de namespace, search_path, objecto reservado, collation,
opclass e metadados inconsistentes. Testar limites, leitura bloqueada, role sem
USAGE/SELECT, alvo errado, interrupção, perda de ligação e limpeza incerta.
Usar duas sessões sincronizadas para demonstrar snapshot coerente durante DDL
concorrente e reobservação após commit. Confirmar ausência de alterações a
dados, histórico, schema e configuração persistente.

F6-005B acrescenta testes golden e integração para os três resultados de drift,
prefixo aplicado com pendentes, divergências de ficheiro/histórico, histórico
ausente, referência inválida, alterações confirmadas e `UNKNOWN` com diferenças
parciais. Verificar spoofing/troca de relação do histórico e recusa sem controlo
de DDL. Reutilizar schemas isolados da base de testes e a matriz local/CI
existente, sem alterações aos dados do playground. Cada PR requer os gates
existentes e validação da main pós-merge.

Fontes PostgreSQL 17. [Isolamento](https://www.postgresql.org/docs/17/transaction-iso.html),
[modo de transacção](https://www.postgresql.org/docs/17/sql-set-transaction.html),
[locks](https://www.postgresql.org/docs/17/explicit-locking.html),
[relações](https://www.postgresql.org/docs/17/catalog-pg-class.html),
[colunas](https://www.postgresql.org/docs/17/catalog-pg-attribute.html),
[constraints](https://www.postgresql.org/docs/17/catalog-pg-constraint.html),
[índices](https://www.postgresql.org/docs/17/catalog-pg-index.html) e
[timeouts](https://www.postgresql.org/docs/17/runtime-config-client.html).
