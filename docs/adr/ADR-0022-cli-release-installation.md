# ADR-0022 — Perfil de instalação e confiança da distribuição da CLI

Estado **Aceite** para implementação e testes de P0-02 em Windows e Linux.
A aprovação abrange bootstrap autenticado, identidade e confinamento,
activação, PATH, recuperação e confiança da distribuição. A distribuição
pública e a utilização de chaves de produção exigem aprovação separada.
O workflow de staging continua a usar a sua chave efémera.

## Contexto

O instalador precisa de confiar no bootstrap, nos bytes do pacote e no
destino antes de colocar um executável no PATH do utilizador. Uma
assinatura Ed25519 sobre um manifesto prova a origem apenas quando o
verificador e a chave vêm de uma fronteira autenticada independente.

## Decisão

- Distribuir um bootstrap versionado e autenticado por um canal de release
  controlado. Obter a âncora de confiança fora do pacote a verificar. Manter a
  chave privada fora do código e dos workflows de PR. Produzir dois builds
  isolados e comparar hashes ou, quando o empacotamento impedir igualdade,
  publicar proveniência verificável com o commit e a toolchain fixados.
- Usar manifestos canónicos assinados por versão e perfil, com SHA-256 do
  pacote. As versões publicadas são imutáveis. Preparar rotações com uma
  delegação assinada pela chave anterior e um número de época monotónico.
  Revogação por comprometimento exige nova âncora pelo canal independente.
  O instalador recusa épocas antigas e metadata expirada, mesmo com
  assinatura válida. Validar o relógio e a política de recuperação antes de
  fixar o formato do manifesto de release.
- Criar uma root por utilizador. No Windows candidato, validar volume NTFS
  local, SID do owner, ACL efectiva e cadeia de ancestrais. Em Linux
  candidato, validar UID, modo, montagem local e ancestrais. Provar identidade
  e confinamento em torno da troca atómica e rejeitar reparse points,
  symlinks e perfis que não consigam garantir a operação. Escritores com a
  mesma identidade do utilizador ficam na fronteira de confiança do perfil.
- Manter versões autenticadas lado a lado. Seleccionar a versão por um
  único apontador substituído atomicamente sob o lock e um launcher estável.
  Uma actualização normal só avança. Um rollback exige pedido explícito e
  pode seleccionar uma versão instalada e novamente verificada, dentro da
  política de segurança vigente. Distinguir um rollback permitido de uma
  tentativa de repor metadata antiga ou um binário revogado.
- Em Windows candidato, acrescentar uma única entrada própria ao PATH de
  utilizador, preservando o tipo e as entradas existentes. Detectar alterações
  concorrentes e recusar colisões ou shadowing conhecidos. Em Linux, começar
  com perfis Bash que consigam activar uma pasta confiável por utilizador.
  Validar separadamente shells login e interactivos antes de aprovar qualquer
  alteração a ficheiros de arranque. A desinstalação retira apenas a entrada
  criada pelo instalador e preserva ficheiros alterados pelo utilizador.
- Separar visibilidade atómica de durabilidade. Manter uma operação
  recuperável e revalidar os bytes antes de retomar. Provar interrupções
  durante instalação, troca e remoção. Documentar o que sobrevive a terminar
  um processo e o que exige garantias adicionais do filesystem após perda de
  energia.
- Distribuir primeiro com Java 21 externo se a validação do runtime
  empacotado estiver pendente. A variante empacotada precisa de testes JDBC,
  TLS, launchers e actualização de segurança, além de licença de redistribuição.

## Alternativas

Um script obtido e executado a partir do próprio pacote cria uma confiança
circular. Trocar directamente o PATH a cada versão mistura a activação com a
escrita do pacote e dificulta a recuperação. Um launcher estável limita o
número de edições persistentes, mas exige um protocolo de identidade e
revalidação do apontador.

## Critérios para aceitar

Validar um consumidor fora do checkout em Windows e Linux sem elevação,
primeira instalação, repetição, update, rollback, PATH persistente num novo
terminal, desinstalação e recuperação. Executar testes de ACL/UID, aliases,
shadowing, alteração externa, artefactos e metadata adulterados, rotação e
revogação. Reconciliar resultados incertos sem retries automáticos. A
instalação da CLI e a autorização de escrita PostgreSQL são contratos
independentes.
