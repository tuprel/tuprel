# F6-001 — Entrega e revisão técnica

Base: `6c771ef` (main após Fase 5). Branch:
`codex/f6-001-cli-migration-foundation`. Estado: pronta para revisão, sem merge.
Contratos de DB permanecem propostos; este relatório não os aprova.

## Implementado

- CLI help/version, init exclusivo e reutilização dos comandos existentes com
  `--schema`, check/dry-run e mensagens redigidas. Sem framework CLI novo.
- `tuprel-migrate`: IDs, catálogo read-only versionado, snapshots imutáveis,
  SHA-256 exacto, validação de nomes/paths/UTF-8/LF e limites de recursos.
- GeneratedSourceWriter ganhou apenas preview de escrita com as mesmas regras
  de ownership; schema parser/validator/formatter/codegen são os existentes.
- Gates da raiz incluem o novo módulo e JavaDoc com warnings fatais. CI exige
  os três testes de symlink no Linux e smoke do launcher real em Bash.
- RFC-006 revisto, contrato CLI e ADRs 0012–0014 propostos. O adapter recomendado
  é um módulo separado futuro, evitando contaminar o driver CRUD com tooling.

## Validação local (Windows, Java 21)

| Suite | Aprovados | Falhados | Ignorados |
|---|---:|---:|---:|
| Build logic/TestKit | 6 | 0 | 0 |
| Schema | 26 | 0 | 0 |
| Codegen | 22 | 0 | 1 |
| CLI | 20 | 0 | 1 |
| SQL | 9 | 0 | 0 |
| Runtime | 51 | 0 | 0 |
| PostgreSQL | 40 | 0 | 0 |
| Migrate | 44 | 0 | 1 |
| Playground | 2 | 0 | 0 |
| **Total** | **220** | **0** | **3** |

PostgreSQL inclui 25 testes de integração reais em `tuprel_devtest`, sem
Docker/Testcontainers local. Os três skips correspondem a symlinks sem
privilégio no Windows; skip no Linux falha o gate. Os ficheiros/IDs/hash têm
fixtures adversariais reais, incluindo CRLF, UTF-8 inválido, traversal,
case aliases, ficheiros alterados e limites de bytes/contagem.

Executados: build completo, check final, JavaDoc sem warnings, installDist,
smoke real `tuprel.bat` em PowerShell numa pasta com espaços, build do
playground e checkConnection. Raiz e playground foram executados sequencialmente.
Ligação local confirmou PostgreSQL 17.11/5432. Query de verificação confirmou
dados do playground presentes e zero schemas temporários de teste restantes.
`jdeps --list-deps tuprel-migrate.jar` devolveu exclusivamente `java.base`.

Falhas intermédias corrigidas: warning Error Prone de comparação de filesystem
e documentação JavaDoc incompleta. Nenhum gate foi desactivado/suprimido.
Resultados finais sem warnings de compilação/JavaDoc. Versões externas e
verification metadata não foram alteradas; novo lockfile cobre o módulo puro.

Linux/Bash e CodeQL devem ser confirmados na CI do head do PR. macOS não foi
executado nesta máquina. SQL de migrations, histórico, locks, drift, falhas de
commit e deploy não foram executados: esses componentes não existem nesta fatia.

## Revisão de arquitectura e segurança

Sem ciclo ou dependência de migrate para CLI/runtime/JDBC. Não há parser SQL
simulado, classificador regex de segurança ou comandos vazios. Um snapshot de
SQL desconhecido não é aprovado para execução. Não há `.env` de produção ou
ligação nova no código desta fatia; fixtures existentes continuam isoladas.

Risco de exposição de source em diagnostics: mitigado com códigos/linha/coluna
e sugestões sem valores ou mensagens cruas, com testes de credenciais sintéticas.
Risco de overwrite no init: mitigado por criação exclusiva, recusa de paths
existentes/case aliases e teste de dois inicializadores concorrentes.
Risco de adulteração/escape no catálogo: leituras limitadas, NOFOLLOW_LINKS,
verificação de paths/atributos e hash exacto; snapshots não retêm buffers mutáveis.

Limitações deliberadas: filesystem controlado pelo developer, sem garantia de
sandbox contra um processo hostil concorrente; init não é uma transacção
multi-ficheiro e uma falha parcial requer inspecção. O catálogo exige root
canónica e formato v1; não aceita aliases nem normaliza ficheiros editados.
Nenhum bloqueador conhecido para a fundação offline; aprovação dos contratos
de ficheiros/CLI ainda pertence à revisão do PR.

## Decisões e próxima entrega

1. Aprovar ADR-0012: formato v1/limites, novo módulo e contrato CLI.
2. Aprovar ADR-0013/0014 antes de qualquer código DB: adapter separado, histórico
   de sucessos confirmados, lock de sessão, subconjunto SQL e recovery incerto.
3. Próxima entrega recomendada: F6-002A, `migrate create` offline, após aprovação
   do formato. F6-002B implementa status read-only após aprovação dos contratos DB.

Comandos PowerShell/Bash reproduzíveis e roadmap integral em
[`CLI_EXPERIENCE.md`](../product/CLI_EXPERIENCE.md). Bootstrap manual do playground
continua temporário; substituição terá tarefa própria, com baseline e sem reset.

## Ficheiros principais

Criados: `tuprel-migrate/` (build, lock, cinco classes e duas suites),
`ProjectInitializer.java`, `CliExperienceTest.java`, contrato CLI, ADRs 0012–0014,
plano F6-001 e este relatório. Alterados: CLI/build, preview do codegen e teste,
settings/build da raiz, `.gitattributes`, CI, RFC-006, planos da Fase 6,
arquitectura/boundaries, docs de segurança/compatibilidade, README, changelog,
inventário e README do playground. Nenhum ficheiro runtime/JDBC/bootstrap SQL
ou configuração local de credenciais foi alterado.
