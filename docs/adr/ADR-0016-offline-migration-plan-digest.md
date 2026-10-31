# ADR-0016 — Plano offline, riscos e digest contextual

Estado: **aceite** em 2026-10-31 por aprovação explícita do utilizador, F6-003A2.
Depende do ADR-0015 aceite e dos modelos de histórico do #28. Complementa a
arquitectura aceite no ADR-0014. Não implementa runner nem executa SQL em DB.

## Contexto, problema e opções

Checksum de ficheiro não vincula target/histórico/preview e não prova revisão
humana. Um token só de SQL canónico perderia mudanças nos bytes originais.
JSON/toString/serialização Java não definem, por si, um protocolo binário estável.
Propõe-se um envelope binário versionado, limitado e determinístico, sem chave
secreta. Não é assinatura, autenticação ou capability de execução.

Não criar portas de executor, locks ou introspecção ainda. Reutilizar
MigrationFile/Checksum/Id, MigrationHistory e MigrationStatus.compare. Código
específico PostgreSQL continua no adapter; nenhuma dependência externa nova.

## Decisão: target e estado observado

MigrationDatabaseSettings.planTarget(schema) não abre connection. Cria um
fingerprint do URL **já validado**, sem query, schema explícito, username
configurado e modo TLS efectivo. Password excluída. O objecto resultante não
guarda nem imprime URL/username/password; guarda schema validado e SHA-256.
Não usa dotenv nem inventa configuração de produção paralela.

O fingerprint representa configuração, **não atesta um servidor vivo**.
Mantém o texto validado do URL, sem tentar equiparar aliases de host/case.
sslmode explícito igual ao default produz o mesmo contexto; mudar password
do mesmo principal não altera a identidade. UTF-8 estrito, sem normalização
Unicode ou replacement de surrogate inválido.

Histórico observado contém presença/ausência da tabela e todos os campos das
linhas APPLIED. A API offline aceita snapshots do chamador; não prova a sua
proveniência. O futuro runner terá de rederivar target e ler histórico real sob
lock; nunca confiar numa snapshot/plan fornecida como autorização.

## Decisão: plano e riscos

Catálogo completo validado v1, IDs únicos, 1000 ficheiros/16 MiB. Ordenar por ID;
ordem de enumeração e linhas da DB não altera o digest. Reutilizar comparação
de integridade/prefixo. Apenas pending é analisado/renderizado. Applied é ligado
ao digest por identidade/nome/hash e estado; não é reexecutado nem reinterpretado
retroactivamente por uma gramática nova.

Estados: NO_CHANGES, REVIEW_REQUIRED ou BLOCKED. Nunca SAFE. Riscos independentes:

| Risco | Efeito nesta proposta |
|---|---|
| PHYSICAL_DRIFT_NOT_CHECKED | Aviso obrigatório, mesmo sem pending; não aprova schema físico |
| DDL_LOCKS | Revisão de blocking/espera necessária |
| INDEX_WRITE_BLOCKING, INDEX_BUILD_COST | Revisão de impacto de index normal |
| UNKNOWN_SQL, DESTRUCTIVE_SQL, EMPTY_DRAFT | Bloqueiam todo o plano |
| HISTORY_DIVERGENCE | Bloqueia por alterações/remoções/renames/gaps/ordem |
| TARGET_MISMATCH, TARGET_UNSUPPORTED | Bloqueiam schema diferente, sistema pg_*/information_schema ou relação/index _tuprel_* reservado |
| PLAN_CONFLICT | Bloqueia contradições conhecidas na sequência de objetos/colunas |
| RESOURCE_LIMIT | Bloqueia por limites de ficheiro/programa/plano |

Verificar duplicações de relações/índices/colunas e referências a colunas de
tabelas criadas no próprio plano. Uma relação exigida como existente antes de
um CREATE TABLE posterior é uma contradição. Não assumir que uma tabela externa
tem apenas as colunas acabadas de adicionar. Existência/tipos/partições etc.
de relações externas continuam desconhecidos; não há catálogo físico simulado.
Basenames de relações e índices `_tuprel_*` são reservados, case-insensitive:
artefactos do utilizador não podem criar/alterar/indexar o próprio histórico
nem colidir com os seus índices. O bootstrap interno é um caminho confiável
separado, não uma escape hatch de SQL raw para migrations do utilizador.

Limites adicionais: 4096 statements e 16 MiB de preview no plano completo.
Resultado imutável; scripts contêm apenas metadados/riscos/diagnóstico/checksums.
Preview global só existe se o plano inteiro for conhecido e consistente.
BLOCKED nunca devolve prefixo SQL parcial. NO_CHANGES devolve texto vazio e
continua a indicar drift físico não verificado.

Preview preserva ordem por migration/statement, com header LF `-- Migration
<id>_<name>`, seguido do renderer do ADR-0015. Não contém source de SQL desconhecido.
Artefactos/checksums originais e preview canónico são vinculados separadamente;
não reescrever ficheiros para normalizar SQL. Nenhum comando CLI novo é exposto.

## Protocolo binário v1

Os identificadores de gramática/preview/policy mantêm os bytes `-proposal`
originais como parte normativa do protocolo aprovado. Registar aceitação não
muda versões, vetores golden ou digests existentes.

SHA-256 dos bytes exactos abaixo; resultado hex lowercase. S = comprimento
em **int32 big-endian** + UTF-8 estrito, sem BOM/normalização. I32/I64 assinados
big-endian; B é exactamente um byte 0/1. Arrays prefixados por I32 count.
Instants = I64 epoch-second + I32 nano-of-second (não total de nanos).
Riscos = count + S(nome), ordenados por String.compareTo, nunca ordinal.

Target:

```text
S("TUPREL-PG-TARGET"), I32(1), S(url-sem-query), S(schema), S(username), S(tls-mode)
```

Plano:

```text
S("TUPREL-MIGRATION-PLAN"), I32(1)
S(grammar-version), S(preview-version), S(policy-version), S(format-marker-com-LF)
S(target-fingerprint-hex), S(schema)
B(history-initialised), I32(history-count)
  rows ordenadas por ID: S(id), S(name), S(checksum-hex), Instant(start),
                        Instant(finish), I64(execution-millis), S(engine-version)
I32(catalog-count)
  todos os ficheiros ordenados: S(id), S(name), S(original-checksum-hex)
S(plan-state), Risks(global), S(SHA256(preview-global-ou-vazio)-hex)
I32(pending-count)
  S(id), I32(parsed-statements), B(has-preview-checksum), [S(preview-checksum-hex)]
  B(has-diagnostic), [S(kind), I32(line), I32(utf16-column)], Risks(script)
```

Versões actuais: `postgresql-ddl-v1-proposal`,
`postgresql-ddl-preview-v1-proposal`, `postgresql-plan-v1-proposal`.
Qualquer alteração de semântica/formato exige revisão/versionamento; a aprovação
da proposta pode fixar novos identificadores e invalidar os tokens experimentais.

Vetor calculado também por implementação PowerShell/.NET independente do encoder
Java: URL `jdbc:postgresql://localhost:5432/demo`, schema app, principal reviewer,
TLS disable, histórico inexistente, migration `20261031_120001_first`, bytes
`CREATE TABLE "app"."Thing" ("id" UUID PRIMARY KEY);` seguidos de LF.

| Campo | SHA-256 |
|---|---|
| Target | da2917ea228fc91fe41e388e8213ef1d71d39a93e97017a76c39f4ac4e21a55c |
| Source | 69f987e7d074def7d3953e36a997f59608c6fcb565709cbc423a0ebd44bb1e16 |
| Preview da migration | 96b368e8fe05a1ca823fd1fb38f95f8a9399667b75cc75493f538c34f2de8e08 |
| Preview global | 738424ee5d09a6cc89364c2f035f9a8d256db46e3869fb05c59e62d25b3abbd5 |
| Plano | d1af5be3dd5c6c329e75ca9e5e0e0e2c32be15456cfebf7f54b5b348849879fc |

Segundo vetor: mesmos target/ficheiro, histórico inicializado com essa migration
APPLIED, start Instant(2s,123456789ns), finish Instant(3s,987654321ns), duração
1000ms, engine development. Sem pending, estado NO_CHANGES, risco único
PHYSICAL_DRIFT_NOT_CHECKED e preview vazio. Digest:
`efa69f321156a67ae67e92b61e702605d64b3232412fa5305e62a44cde9a6403`.
Reprodução independente: recurso de testes
`tuprel-migrate-postgresql/src/test/resources/plan-digest-v1-reference.ps1`
(PowerShell 7, apenas fixtures fixas e memória, sem connection/configuração local).

## Segurança, limites e validação

Um digest pode ser calculado por qualquer pessoa; não é prova de aprovação.
Não vincula servidor físico/cluster UUID, dados das tabelas ou drift completo.
TLS/configuração e ACLs operacionais continuam necessários. Aprovação antiga
nunca pode ser reutilizada após sucesso parcial, mudança de artefacto/estado,
troca de target/principal/TLS ou mudança de gramática/renderer/policy.

Semântica de confirmação e precondições reais do executor aceites separadamente
no [ADR-0017](ADR-0017-transactional-runner-gates.md). Aprovar este formato offline
não implementa nem executa DDL. Não usar simples aprovação CI para ultrapassar
estes gates. Os testes locais de role do #28 foram concluídos antes da sua integração.

Testes de golden, mutação de cada campo do histórico/target, bytes e nomes,
ordenação, imutabilidade, concorrência da facade, conflitos, unknown/destructive,
limites e target não canónico. Nenhuma ligação à DB ou leitura de ficheiros.
