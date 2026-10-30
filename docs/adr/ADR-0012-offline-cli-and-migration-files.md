# ADR-0012 — Fundação offline da CLI e formato de migrations

Estado: **aceite** em 2026-10-30, por aprovação explícita do contrato CLI e
do formato offline v1 do PR #26. Os ADRs 0013/0014 continuam propostos.
Data: 2026-10-30. Relacionados: ADR-0001/0002/0007, RFC-006.

## Problema

A CLI existente tem três comandos reais, exit codes 0/1/2 e uma distribuição
Gradle application. Falta onboarding, ajuda, versão e um contrato de ficheiros
independente de JDBC. Acrescentar execução antes destes contratos cristalizaria
decisões operacionais ainda não aprovadas.

## Decisão

1. CLI JDK-only, sem framework adicional. Preservar argumentos posicionais,
   default `tuprel/schema.tuprel`, `format --check`, `generate --check` e códigos
   0/1/2. Acrescentar `--schema`, help, versão e dry-run, conforme
   [contrato CLI](../product/CLI_EXPERIENCE.md).
2. Reutilizar compiler/formatter/codegen e ownership do GeneratedSourceWriter.
   A única extensão deste writer é uma consulta sem escrita `wouldChange` que
   executa a mesma validação de ownership usada pela escrita.
3. Criar `tuprel-migrate` como biblioteca **JDK-only**, sem JDBC, runtime,
   PostgreSQL, schema ou codegen nesta fatia. Tem política de dependências e
   responsabilidade próprias: identidade, bytes, integridade e catálogo offline.
   CLI → migrate serve efectivamente o marker criado pelo init; não é um módulo vazio.
4. Formato v1: marker obrigatório `migrations/.tuprel-migrations` com bytes
   exactos `TUPREL-MIGRATIONS 1\n`; directorias `uuuuMMdd_HHmmss_nome` e apenas
   `migration.sql` em cada uma. Preserva o separador data/hora da especificação.
   Data UTC válida, ano 0001–9999, segundos 00–59; um ID por catálogo.
   Nome: 1–64 caracteres ASCII minúsculos, palavras separadas por `_` ou `-`.
   Não normalizar nomes, colidir IDs ou aceitar aliases de case.
5. SQL UTF-8 estrito sem BOM/NUL/CR, final LF e conteúdo não branco. SHA-256
   dos bytes exactos. Limites v1: 1 MiB/ficheiro, 16 MiB/catálogo, 1000 migrations.
   Alterações destes limites/formato exigem revisão de compatibilidade.
6. `MigrationFile` guarda snapshot imutável em memória; `toString` omite SQL.
   Reader recusa paths com `..`, roots não canónicas, links/aliases e entradas
   inesperadas, limita leituras e verifica identidade/tamanho/mtime após leitura.
   Resultados são ordenados por ID e devolvidos como lista imutável.
7. Init cria exclusivamente `tuprel/schema.tuprel` e o marker numa pasta nova.
   Recusa `tuprel` existente, incluindo aliases de case e links. Não altera
   dependências, builds, configs ou DB. Falhas parciais deixam evidência para
   inspecção; não se apagam ficheiros automaticamente para tentar rollback.

## Alternativas e trade-offs

- Framework CLI: boa ajuda automática, mas nova dependência sem necessidade nos
  quatro comandos actuais; reavaliar quando os comandos de DB o justificarem.
- SQL CRLF normalizado antes do hash: esconde alterações; rejeitar e pedir
  correcção explícita. Git conserva LF e os testes criam CRLF adversarial real.
- Sem marker: onboarding ligeiramente mais curto, mas perde versionamento e
  directorias vazias no Git. O marker fixa a interpretação antes do histórico.
- Manifesto do codegen reutilizado para migrations: ownership de `.java` e
  substituição de outputs têm contratos diferentes de migrations imutáveis.
  Reutiliza-se esse mecanismo no gerador; o reader de migrations não o copia.
- Ler todos os scripts sem limite: rejeitado por consumo não limitado de memória.
  Os limites conservadores podem exigir uma decisão futura para backfills grandes.

## Limites e aprovação

Validação de ficheiros **não é parsing SQL nem autorização**. SQL com `COMMIT`,
`DROP` ou operações desconhecidas pode ser armazenado e lido como dados, sem
ser aprovado para execução. A F6-001 não disponibilizou comandos migrate;
a F6-002A acrescenta exclusivamente criação offline de drafts.

O filesystem tem de ser local e controlado pelo developer. Leitores e editores
exigem exclusão de escritas concorrentes; criadores da F6-002A cooperam através
do protocolo descrito no [plano da tarefa](../../plans/F6-002A_MIGRATION_CREATE.md).
Os checks não constituem sandbox contra outro processo com as
mesmas permissões a trocar directorias durante I/O. Snapshots não guardam um
path que um runner posterior volte a abrir silenciosamente. Roots devem usar
paths canónicos; a CLI resolve a working directory antes de criar a estrutura.

Formato v1, módulo puro e contrato CLI aprovados. Os ADRs 0013/0014 exigem
aprovação separada antes de qualquer implementação de DB.

## Validação

Testes de bytes/checksum conhecidos, datas, ordenação, colisões, snapshots,
limites, traversal, links, CLI antiga/nova, concorrência do init e dry-run.
Linux exige os testes de symlink executados; Windows sem privilégio reporta skip.
Os novos APIs mantêm Java 21. Testar novos JDKs LTS é uma expansão futura da
matriz; não altera `--release 21` nem o runtime mínimo sem novo ADR.

Referência: [criação exclusiva no JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/StandardOpenOption.html#CREATE_NEW).
