# F6-002A — Criação offline de migrations

Base: PR #26 integrado em `bf057d5`; CI e CodeQL da main aprovados.
ADR-0012 aceite por autorização explícita. ADRs 0013/0014 continuam propostos.
Esta tarefa só cria ficheiros: sem JDBC, histórico, parser SQL ou execução.

## Contrato implementado para revisão

`tuprel migrate create --name <nome> [--dry-run]` usa o catálogo existente
`tuprel/migrations`, relativo à working directory. Não procura projectos em
pastas superiores, não lê schema/configuração/env e não executa init implícito.

- Nome canónico do ADR-0012: `[a-z][a-z0-9]*(?:[_-][a-z0-9]+)*`, 1–64 ASCII.
  Maiúsculas, espaços, Unicode e paths falham. Não há normalização silenciosa.
- ID UTC `uuuuMMdd_HHmmss`, ano 0001–9999, derivado uma única vez do relógio
  por plano. A API aceita Clock, mas não existe opção CLI para forjar IDs.
- IDs repetidos falham independentemente do nome. Relógio anterior ao último
  ID falha; não se incrementam segundos artificialmente nem se fazem retries.
- Pasta criada exclusivamente e `migration.sql` aberto com CREATE_NEW e
  NOFOLLOW_LINKS. Bytes UTF-8/LF, comentário mínimo sem statements executáveis.
  Releitura pelo catálogo existente verifica conteúdo/checksum após escrita.
- Catálogo/marker/limites validados pelo código existente. Não alterar v1.
- Dry-run não escreve nem cria lock; não reserva ID nem garante permissões
  futuras. Revalidação integral na criação real. O estado pode mudar depois.
- Sucesso/preview em stdout, falhas em stderr; exit 0 ou 2. Help não acede
  ao filesystem. Nenhum comando de DB/futuro aparece como disponível.

## Concorrência e footprint

O catálogo v1 só admite marker e pastas de migrations. A coordenação usa um
ficheiro **irmão**, `tuprel/.migrations.create.lock`, regular, vazio e sem links.
Não contém PID, credenciais ou histórico. Não é indicador de lock activo.
Um FileChannel exclusivo adquire `tryLock(0, 1, false)`, sem bloquear/repetir;
o OS liberta o lock ao fechar o canal ou terminar o processo.

O ficheiro permanece: apagar e recriar permitiria dois locks sobre inodes
diferentes. Não apagar, substituir nem fazer checkout desse ficheiro com
criadores activos. Pode ser ignorado no Git pelo proprietário do projecto;
a CLI não modifica `.gitignore` nem outras configurações.

Um tryLock JVM global impede canais concorrentes no mesmo processo. É
deliberadamente conservador: dois catálogos diferentes no mesmo JVM também
podem receber BUSY. Evita um registry de locks de lifetime complexo para uma
operação curta. O canal é separado do marker: leitores abrem/fecham o marker,
e em alguns sistemas fechar outro canal do mesmo inode liberta locks do JVM.
Ver [contrato FileLock do JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html).

Cooperação só entre criadores Tuprel num filesystem local com locks suportados.
Sem promessa de exclusão de editores externos, filesystem remoto ou mutações
hostis entre checks e I/O. Leitores/editores devem excluir criação concorrente.
Sem dependências novas e sem alteração do grafo CLI → migrate → java.base.

## Falhas e recuperação manual

Antes de alocar a pasta, falhas deixam no máximo o ficheiro vazio de coordenação.
Depois de alocá-la, qualquer falha I/O reporta PARTIAL_CREATION e preserva tudo.
Não apagar/recriar automaticamente: pode haver alterações de outro processo.
Inspeccionar a pasta nova, corrigir ou remover deliberadamente apenas o draft
nunca aplicado, e só depois repetir. Não remover o ficheiro de lock para isso.

Pasta vazia/SQL incompleto bloqueia descoberta; colisões bloqueiam o mesmo ID.
Uma falha após escrita completa pode deixar um ficheiro válido: sem journal ou
histórico não é possível inferir confirmação de sucesso apenas pelos bytes.
Nesse caso a CLI reporta falha e exige inspecção, sem retry automático.
Não há garantia de durabilidade contra falha de energia nem transacção de
filesystem; formato válido continua sem significar SQL aprovado ou aplicável.

## Validação

Testes de UTC, nomes, preview sem escrita, colisões, relógio regressivo, limites,
permissões reais (ACL/DOS no Windows, POSIX no Linux), symlinks, escrita parcial,
CREATE_NEW e concorrência por barreira/JVM separado, sem sleeps de sincronização.
Launchers reais PowerShell/Bash e comandos antigos fazem parte da validação.
Symlinks sem privilégio no Windows reportam skip; gate Linux exige execução.
Manter root/build PostgreSQL local e playground sequenciais, com CI completa.

Validação local em 2026-10-30, JDK 21.0.13/Windows e PostgreSQL 17.11:

| Verificação | Resultado |
|---|---|
| `build :tuprel-cli:installDist -PtuprelTestDatabase=local --dependency-verification strict` | Passou, incluindo check, JavaDoc com Werror e integração real |
| Relatórios root + build-logic | 240 aprovados, 4 symlinks ignorados por privilégio Windows, 0 falhados |
| Novos testes migrate/CLI | 23 casos: 22 aprovados e 1 symlink ignorado, incluídos no total anterior |
| Integração PostgreSQL | 25 aprovados, executados nesta validação em schemas isolados |
| Playground `build checkConnection` (depois da raiz) | Passou; 2 testes aprovados, tarefas sem mudanças reutilizadas |
| Launcher `.bat` via PowerShell | 19 códigos de saída esperados; pasta com espaços/acentos; draft UTF-8/LF e preview sem escrita |
| `jdeps` do JAR migrate | Só `java.base` |

A primeira tentativa encontrou um warning de JavaDoc na excepção nova; a
documentação foi corrigida e Werror manteve-se activo. Nenhuma falha final.
Linux/Bash e os quatro testes de symlinks são verificados na CI do PR;
macOS não foi executado localmente. A build incremental reutilizou testes de
módulos sem mudanças; CI limpa mantém toda a matriz existente.

Bootstrap e dados do playground preservados: verificação read-only confirmou
4 clientes, 8 produtos, 4 encomendas, 8 itens e 0 schemas de integração restantes.
Comandos PowerShell/Bash para reprodução em [CLI_EXPERIENCE](../docs/product/CLI_EXPERIENCE.md).

Próximo passo: rever F6-002A e aprovar ADRs 0013/0014 antes da F6-002B.
Não adoptar migrations no playground nem implementar status nesta tarefa.
