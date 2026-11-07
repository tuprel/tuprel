# ADR-0021 — Revisão para decisão de C2 e C3

Direcção **aceite para implementação incremental**. Cada perfil de escrita
depende de validação técnica. A aceitação abrange o desenho registado no
[#56](https://github.com/tuprel/tuprel/pull/56). A disponibilidade é acompanhada
no [plano de execução](../../plans/PHASE_6_EXECUTION.md).

## C2 — Aprovisionamento do primeiro projecto

Proposta: aprovisionamento administrativo fora de `migrate dev`. Um serviço
controlado cria um schema novo com ownership exclusivo de migrations e
regista a criação numa fonte de confiança que o projecto e os utilizadores da
aplicação não podem alterar. O registo liga o projecto e o ambiente à geração
do alvo no inventário do serviço, ao fingerprint, à identidade do cluster
verificada pelo operador, aos OIDs do database e schema e à identidade do
actor. O serviço emite uma autorização assinada, de uso único, com audiência,
expiração e nonce. A identidade de geração muda em restore, clone, failover
sem continuidade verificada ou recriação. OIDs e nomes isolados são
insuficientes para estabelecer continuidade.

O bootstrap requer validação dessa autorização, janela administrativa sem
DDL externo, lock de sessão e reobservação completa do namespace vazio, com
histórico ausente. A entrada activa do inventário, num ledger administrativo
privilegiado no mesmo database e fora do schema de aplicação, recebe o nonce
e o OID de histórico na mesma transacção que cria o histórico canónico vazio.
O ledger impõe unicidade do nonce e mantém uma geração activa por namespace.
Os privilégios e o
fluxo de recuperação do ledger fazem parte da decisão de aprovisionamento.
Após confirmação, a C1 pode validar o prefixo gerido de comprimento zero.
A transacção limita-se ao recibo e ao histórico canónico vazio.

Schema preexistente sem prova, objectos fora do perfil, registo ausente,
autorização expirada, reutilizada ou de outro alvo impedem o bootstrap.
Um `UNKNOWN` do observador conserva o seu significado. Uma perda da
confirmação de commit suspende a operação. O serviço verifica, numa nova
sessão e sob controlo, o par recibo/histórico, a geração e o inventário. Uma
divergência exige intervenção humana. Testar replay da autorização, alvo
trocado, schema preexistente vazio, mudança de geração, rollback e commit
incerto em PostgreSQL real.

### Perfil local e primeiro incremento

O provisionador pode ser um processo administrativo local em Java 21,
executado pelo operador da instância PostgreSQL 17. Usa um database de
desenvolvimento dedicado, configuração de ligação explícita e uma chave
Ed25519 privada guardada fora do projecto com acesso exclusivo do operador.
O executor recebe apenas a chave pública, audiência, geração activa e
identidade do ledger através da configuração administrativa. A geração
configurada é independente da base e deve ser rodada antes de retomar
escritas após restore, clone ou mudança de continuidade.

O operador cria o namespace novo e regista a sua identidade no ledger
administrativo antes de emitir a autorização. A role administrativa detém
o namespace e o ledger e mantém a janela sem DDL concorrente. O executor
revalida o identificador do cluster, os OIDs de database e namespace,
fingerprint, permissões e registo activo. O nonce do recibo é único e
o OID de histórico tem de coincidir na reconciliação. Se o commit for
incerto, a reconciliação corre numa sessão nova em leitura e permite
distinguir recibo confirmado, ausência comprovada e estado inconclusivo.

O envelope interno v1 assina com Ed25519 os 64 caracteres ASCII do SHA-256
canónico, em minúsculas. O payload enquadra domínio e versão, audiência,
fingerprint, identificador do cluster, OIDs de database, namespace e ledger,
geração, nonce e expiração em segundos e nanos. Strings usam o framing
UTF-8 com comprimento do ADR-0021. A identidade da role e a janela sem DDL
externo são condições do provisionador. ACLs observáveis podem excluir
grants de escrita directos. A exclusão de administradores e membros com
capacidade de assumir a role continua sob controlo operacional.

O primeiro incremento é interno ao adapter e exercitado apenas em
fixtures. O teste cria a chave em memória e instala um ledger e namespace
isolados. Pode ser corrido em PostgreSQL local sem serviço cloud.

```powershell
.\gradlew.bat :tuprel-migrate-postgresql:integrationTest --tests '*InitialBootstrapIntegrationTest' -PtuprelTestDatabase=local
```

A distribuição do provisionador, o armazenamento local da chave e as
instruções de instalação para utilizadores são gates de exposição. A CLI
actual continua a oferecer preview para projectos com histórico gerido.
[Identidade do cluster PostgreSQL](https://www.postgresql.org/docs/17/functions-info.html#FUNCTIONS-CONTROLDATA).

## C2 — Publicação atómica sem sobrescrita

Exigir um rename atómico com garantia *no-replace*
da plataforma. O lock JVM e de ficheiro existente continua a serializar
escritores Tuprel, mas uma verificação prévia de ausência do destino não
resolve a corrida com um escritor externo. A API `Files.move` com
`ATOMIC_MOVE` deixa o comportamento perante um destino existente ao
provider. A via puramente NIO fica fora do contrato de escrita.
[Java 21 Files.move](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).

| Plataforma candidata | Primitiva e limites |
|---|---|
| Linux, ext4 e XFS locais | `renameat2(RENAME_NOREPLACE)` com descritores de directório e basenames. Exigir kernel e filesystem com suporte comprovado no alvo. `EEXIST` é colisão e ausência de suporte é recusa. |
| Windows, NTFS local | `SetFileInformationByHandle(FileRenameInfo)` no handle de staging, `ReplaceIfExists=FALSE` e destino canónico de volume GUID obtido do handle de catálogo retido. Os ancestrais permanecem abertos sem partilha de remoção. A API documenta a recusa de substituição. A publicação da pasta completa no mesmo volume tem de passar nos testes NTFS antes de ser suportada. |
| Outros providers e filesystems | Preview. Inclui volumes de rede, overlay e combinações de filesystem/OS sem validação específica. |

Fontes da matriz. [Linux renameat2](https://man7.org/linux/man-pages/man2/rename.2.html)
e [Windows FILE_RENAME_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_rename_info).

Confinar staging e catálogo a directórios identificados, controlados e do
mesmo volume. Validar paths, identidades, reparse points, aliases e case sob o
lock partilhado. Criar staging privado fora da descoberta v1, escrever
`migration.sql` com criação exclusiva, confirmar bytes e SHA-256, e mover
a pasta completa. A primitiva protege o nome de destino mesmo se um escritor
externo criar uma entrada nesse instante. A autorização de acesso ao staging
e aos pais continua obrigatória. Um escritor externo pode criar outro
basename com o mesmo ID, ou apagar e editar entradas. Só a exclusão
administrativa desses escritores e o lock cooperativo preservam invariantes
do catálogo inteiro. Uma alteração detectada na redescoberta invalida a
confirmação, mesmo que o rename tenha respeitado o nome de destino. Não usar
copy, replace ou rename não atómico como fallback.

Antes do rename, persistir ID tentado, checksum, geração e identidade do
catálogo num registo recuperável fora da descoberta v1. Guardar apenas
identificadores e digests necessários à reconciliação. Se a preparação
desse registo falhar, recusar antes da publicação.
Confirmar `PUBLISHED` só após reler o catálogo e verificar identidade,
bytes e entradas anteriores. Antes do rename, apenas staging de ownership
comprovado pode ser limpo. Depois de um rename potencialmente executado,
erro de I/O, identidade mudada ou evidência divergente produz
`PUBLICATION_UNKNOWN`. Preservar o registo da operação. Bloquear retry
automático. Redescobrir read-only em workspace controlado e comparar com
o recibo. Correspondência exacta confirma presença. Ausência ou divergência
exige reconciliação humana antes de outra invocação. Nunca apagar, reparar
ou substituir o destino automaticamente.

Separar visibilidade atómica de durabilidade. Em Linux, validar flush do
ficheiro, do directório de staging e dos pais de origem/destino no perfil
local suportado. Um flush do ficheiro sozinho não persiste a entrada de
directório. Em NTFS, `FlushFileBuffers` confirma o flush do ficheiro e não
estabelece um contrato portátil de persistência do rename. A primeira
entrega deve prometer apenas visibilidade atómica e confirmação por leitura.
Após perda de energia, redescobrir e reconciliar. Um erro de flush depois
de o rename poder ter ocorrido é `PUBLICATION_UNKNOWN`.
[Linux fsync](https://man7.org/linux/man-pages/man2/fsync.2.html) ·
[Windows FlushFileBuffers](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers).

Antes de autorizar publicação, testar em NTFS, ext4 e XFS reais. Cobrir
concorrência com create/dev e escritor externo, colisão com ficheiro, pasta vazia e link,
substituição dos pais, mudanças de case, perda de acesso, interrupção antes e
depois do rename, erros de flush e redescoberta. Comparar os bytes anteriores
e verificar que um leitor concorrente vê zero ou uma pasta completa. A via
nativa, a sua distribuição e a matriz de suporte precisam de aprovação antes
de introduzir dependências.

### Distribuição aprovada para C2.2

Executável pequeno em C por plataforma e arquitectura,
distribuído opcionalmente com manifesto de SHA-256 autenticado e build
reproduzível. Java 21 invoca por caminho absoluto fora do projecto, sem
shell, resolução pelo PATH ou procura em directórios graváveis pelo projecto.
O protocolo versionado admite uma operação de escrita no-replace e
observação read-only da identidade no destino. O helper verifica volume,
filesystem, handles dos directórios, identidade da origem e basename de
destino. Java conserva descoberta v1, validação, locking, recibo e
reconciliação. Depois de registar a tentativa, qualquer falha ambígua
produz `PUBLICATION_UNKNOWN` e conserva a evidência. O recibo bloqueia
escritas cooperantes até reconhecimento explícito de um resultado
confirmado. O [contrato do pacote e protocolo](../../native/migration-publisher/README.md)
regista o manifesto Ed25519, a separação de privilégios da instalação e os
limites de confirmação após falhas de energia.

Os testes do helper devem correr em executáveis reais e volumes locais
verificados. Um runner Linux sobre overlay não conta como prova de ext4
ou XFS. A suite exercita um processo escritor separado que cria o destino
imediatamente antes do rename. Um perfil só fica disponível após verificação
da matriz de OS, arquitectura, filesystem, instalação e recuperação.

O primeiro incremento implementa a [primitiva nativa e o protocolo v1](../../native/migration-publisher/README.md).
As provas compilam o auxiliar duas vezes e executam os binários em volumes
locais verificados. A disponibilidade de escrita também exige o cliente Java
com manifesto autenticado, recibo e reconciliação.

## C3 — Transição do protocolo de deploy

Os ficheiros v1 continuam legíveis e os bytes/checksums históricos não
mudam. A autorização de escrita num alvo migrado exige o digest físico do
ADR-0021, com domínio e versão próprios. Um digest legado nunca é promovido
a autorização física. A partir da versão de corte, a operação de escrita
é exclusiva da via nova. Status, diff, previews e auditoria continuam
disponíveis com permissões de leitura.

1. Inventariar owners, memberships, credenciais, sessões, jobs e vias de
   DDL. Congelar deploy. Retirar ownership, privilégios directos e herdados e
   capacidade de assumir roles de escrita às identidades antigas. Revogar ou
   rodar credenciais e terminar sessões antigas. Verificar o corte com um
   executor anterior e uma credencial anterior num alvo de teste.
2. Usar role de ownership sem login e identidade efémera de escrita
   exclusiva de um executor gerido. O emissor de credenciais valida a
   identidade do workload e uma allowlist do artefacto e versão do protocolo
   aprovados. O executor corre artefacto fixado, sem permitir fornecer
   binários ou comandos arbitrários, e os segredos ficam inacessíveis a
   executores antigos e jobs genéricos. PostgreSQL recebe a identidade da
   role. A verificação da versão do binário pertence ao ambiente de execução.
3. Reobservar com o gate físico e exigir novo preview e aprovação para
   cada deploy pendente. Revalidar perfil, estado, artefactos e OIDs sob o
   lock antes da escrita, conforme o ADR. Testar incompatibilidade de digests,
   alterações de target/histórico e bloqueio da via legada antes de activar.
4. Em rollback operacional, congelar escrita, conservar leitura e
   restaurar apenas uma versão que implemente o mesmo gate aprovado.
   Sem uma versão compatível, manter o alvo em leitura até recuperar.
   Administradores ficam sujeitos à janela de controlo e ao procedimento
   de emergência auditado, separado da autorização normal de deploy.

O cutover e os testes da via legada precedem a exposição de `dev --write`.
Se o alvo continuar a aceitar executores antigos com capacidade de escrita,
manter apenas preview. Um formato novo poderia impedir binários antigos de
ler novos ficheiros, mas não retiraria a sua capacidade de escrever com
credenciais privilegiadas. O controlo de acesso continua obrigatório.
[PostgreSQL REVOKE](https://www.postgresql.org/docs/17/sql-revoke.html).

### Prova da via legada

A CI compila a CLI anterior a partir de `48b08e6`. Num alvo PostgreSQL 17
isolado, a prova aplica uma migration com a identidade legada, transfere
ownership, retira os grants de escrita e confirma que nem um digest legado
recalculado permite aplicar a migration seguinte. O status continua
disponível durante a janela de leitura. A fixture Linux usa roles
separadas, confirma a ausência de membership de ownership e testa NOLOGIN
e a terminação de uma sessão anterior. O perfil local confirma a recusa
do launcher com os grants de teste existentes. A conta local conserva
a capacidade de assumir a role de fixture e, por isso, essa execução não
demonstra a exclusão de todos os caminhos de escrita.

Esta prova cobre a retirada da via legada. A via gerida ainda exige um
executor fixado e um emissor que vincule a credencial ao artefacto e
à versão do protocolo. O corte operacional exige autorização por alvo.

## Decisões para aprovação

- Aprovisionamento separado com autorização de uso único, recibo
  transaccional e revisão dos privilégios administrativos.
- Publicação por primitivas nativas, com a matriz inicial proposta e
  reconciliação de `PUBLICATION_UNKNOWN`.
- Gate físico obrigatório num alvo migrado, credenciais exclusivas do
  executor aprovado e cutover verificado antes de disponibilizar a escrita.
