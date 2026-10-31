# README Product Experience 2.0 — revisão

Observação: 2026-10-31. Base: main
`6f66ef47ddffb2c9e6d5e302cf2556536eebf472`. Alteração editorial independente,
na branch `docs/readme-product-experience`. A [página proposta](../../README.md)
documenta apenas o código integrado nessa base. Não altera runtime, builds,
schema, workflows, metadados do GitHub ou a proposta estratégica do PR #34.

A entrega inicial corresponde ao HEAD `39fccd9`. A revisão visual seguinte
mantém o mesmo PR #35 e todos os exemplos/anchors, acrescentando apenas o
workflow SVG e a reorganização do topo. Em 2026-10-31, o utilizador aprovou
a apresentação, a gravação real e a integração do PR #35 após conferir o novo
HEAD. O GIF original é incorporado sem alteração em
[tuprel-cli-review.gif](../assets/readme/tuprel-cli-review.gif), com acesso ao
tamanho original e transcrição textual na secção CLI Experience do README.

## Referências e decisões editoriais

Foram consultados os READMEs públicos actuais, sem reutilizar texto, imagens,
identidade, métricas ou promessas de outros produtos:

- [Prisma](https://github.com/prisma/orm): entradas visíveis para documentação,
  requisitos e onboarding. Manter instruções concretas para começar.
- [Drizzle](https://github.com/drizzle-team/drizzle-orm): posicionamento conciso
  e acesso directo ao manual. Explicar primeiro o valor da ferramenta.
- [jOOQ](https://github.com/jOOQ/jOOQ): exemplos que tornam a API tangível.
  Mostrar a query Java antes dos detalhes de instalação.
- [Exposed](https://github.com/JetBrains/Exposed): distinção entre requisitos,
  capacidades e exemplos. Tornar a compatibilidade fácil de consultar.
- [Kysely](https://github.com/kysely-org/kysely): explicar o benefício dos tipos
  junto da utilização e encaminhar para referência. Não copiar animações.

| PR #35 em 39fccd9 | Revisão visual final |
|---|---|
| Hero só com texto | Tagline curta, descrição e workflow numerado original |
| Benefícios antes da primeira query | Workflow, explicação curta e Java antes dos benefícios |
| Badges distribuídas irregularmente a 320/390 px | Três pares em linhas estáveis, sem reduzir a altura de 20 px |
| Sem gráfico explicativo | Quatro SVGs: horizontal/vertical e Light/Dark, 8 402 bytes no total |
| Exemplos e navegação já completos | Conteúdo técnico e anchors preservados; links contextuais mantidos |
| Só proposta de animação | Gravação ConPTY real aprovada e incorporada com transcrição textual |

A hierarquia usa um H1, H2 por assunto e H3 para os três exemplos de comportamento.
Só se usa HTML suportado pelo GitHub: títulos/parágrafos, picture/source,
links/imagens, br, anchors e details/summary. Sem CSS de layout, tabelas no
README ou novos logótipos. Cores do texto, links e código ficam a cargo do tema.

O workflow mostra schema.tuprel → geração Java → cliente tipado → PostgreSQL,
sem sugerir criação de tabelas. Os SVGs usam formas/texto originais, fontes do
sistema e duas paletas; não contêm scripts, fontes remotas ou recursos externos.
O picture selecciona a versão vertical até 480 px e a variante de cor através
de prefers-color-scheme, conforme o [exemplo oficial do GitHub](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/quickstart-for-writing-on-github).
Sem suporte a picture, a imagem horizontal Light é o fallback.

## Badges e destinos

Todas usam Shields.io, `style=flat` e altura 20. As duas badges dinâmicas
consultam a branch main, não o HEAD deste PR. Os restantes rótulos descrevem
contratos existentes; não são resultados de testes.

Pares: CI/CodeQL, Java/PostgreSQL, licença/maturidade. A licença e a maturidade
perdem apenas os prefixos redundantes no desenho; nomes, alt e destinos mantêm-se.
Três linhas explícitas são um compromisso deliberado com a limitação de layout
do GitHub: mantêm os pares juntos também a 320 px, sem CSS ou agrupamentos frágeis.

| Badge | Destino |
|---|---|
| CI | [Workflow CI da main](https://github.com/tuprel/tuprel/actions/workflows/ci.yml?query=branch%3Amain) |
| CodeQL | [Workflow CodeQL da main](https://github.com/tuprel/tuprel/actions/workflows/codeql.yml?query=branch%3Amain) |
| Java 21 | [Instalação e baseline](../getting-started/installation.md) |
| PostgreSQL | [Configuração local](../../examples/tuprel-playground/README.md#configure-a-dedicated-local-database) |
| Apache 2.0 | [Licença](../../LICENSE) |
| Development Preview | [Compatibilidade e estado](../../README.md#compatibility-and-project-status) |

Shields.io é uma dependência externa de apresentação e pode ter cache ou falhas.
Os links e textos alternativos preservam o acesso à informação. Não existem
badges de versões, downloads, cobertura ou benchmarks.

## Validação técnica

- Na entrega inicial 39fccd9, `check` da raiz e distribuição CLI aprovados com JDK 21 e verificação estrita
  de dependências. Os 25 casos de integração PostgreSQL foram executados de
  novo na base local dedicada, em schemas isolados, sem Docker. Relatórios
  unitários: 215 aprovados, quatro skips de symlink por privilégio Windows;
  tarefas unitárias sem alterações foram reutilizadas pelo Gradle.
- Build do playground aprovada, sequencialmente depois da raiz; os dois testes
  sem alterações foram reutilizados. Não foram executados run/bootstrap.
- Schema compacto do README validado, gerado e confirmado por generate --check.
  Os seus 34 ficheiros Java gerados compilaram com
  `javac --release 21 -encoding UTF-8 -Xlint:all -Werror`.
- Cinco fences Java conferidos, incluindo o bloco de imports. Cada um compilou
  separadamente com os imports exactos documentados; os quatro fragmentos de
  comportamento também compilaram juntos num método. Repetição com o cliente
  compacto e com o cliente completo do playground: 12 verificações de compilação.
  Esta compilação foi repetida nesta revisão. Os seis blocos Java/schema e os
  30 headings/anchors do HEAD 39fccd9 foram comparados e preservados.
- Preview executado com um DataSource que lança AssertionError em qualquer
  chamada JDBC: produziu SELECT com placeholder e um bind separado, sem ligação.
  Queries, includes e transacções do README foram compilados; não foram executados
  como uma nova demonstração na base. Os testes PostgreSQL existentes validam
  esses comportamentos, sem alterar os dados do playground.
- Quickstarts e comandos CLI exercitados em duas pastas temporárias novas,
  com tuprel.bat/PowerShell e launcher Unix/Git Bash: init/dry-run, validate,
  generate/check/dry-run, format --check, help/version e migrate create/dry-run.
  Clone e build iniciais foram substituídos pelo checkout existente e pelo build
  real já executado. Git Bash no Windows não equivale a Safari, Linux ou macOS.
- Os 62 links internos do README e deste relatório (56 e seis, respectivamente),
  incluindo href/src/srcset HTML e fragments, resolvem com case exacto.
  Os 16 anchors anteriores à primeira reformulação,
  explícitos e de headings, também foram preservados. Os nove destinos externos
  únicos do README responderam HTTP 200;
  os seis SVGs continham os rótulos esperados, incluindo CI/CodeQL passing.

A revisão visual 557e455 não repetiu os testes locais PostgreSQL. Para a
incorporação aprovada do GIF, o check da raiz voltou a passar, incluindo os
25 testes PostgreSQL locais em schemas isolados; tarefas unitárias sem mudanças
foram reutilizadas. O build do playground passou depois da raiz, reutilizando
os seus dois testes. Os 12 checks de compilação dos exemplos também passaram.
A CI completa continua obrigatória no novo HEAD, sem alterações aos workflows.

Comandos de build reproduzíveis em PowerShell com JDK 21 activo:

```powershell
.\gradlew.bat check :tuprel-cli:installDist --dependency-verification strict -PtuprelTestDatabase=local
# Depois da raiz:
.\gradlew.bat -p examples/tuprel-playground build --dependency-verification strict
```

Para conferir os fragmentos: guardar o schema da página numa pasta temporária
como tuprel/schema.tuprel; executar os três comandos validate/generate/check
documentados. Compilar os Java gerados para um directório temporário. Usar como
classpath esse directório e os build/classes/java/main de tuprel-runtime,
tuprel-postgresql e tuprel-sql. Envolver cada fragmento num método de classe
com os imports da página e campos DataSource dataSource, TuprelClient db,
UUID orderId e UUID productId. Repetir substituindo apenas o directório das
classes geradas por examples/tuprel-playground/build/classes/java/main.
Os scripts locais de conferência são evidência auxiliar ignorada pelo Git;
nenhum harness, módulo ou dependência nova integra esta entrega editorial.

Os quatro testes de symlinks continuam activos e obrigatórios em Linux na CI.
Os checks do PR são conferidos no HEAD publicado e reportados na descrição;
não usar as badges da main como prova da CI do PR.

## Revisão visual final

| Superfície | Resultado observado |
|---|---|
| GitHub Light, desktop | Workflow horizontal carregado a 720 × 104; Java antes dos benefícios |
| GitHub Light, 390 × 844 | Variante vertical; três pares de badges; página/largura útil 375 px |
| GitHub Light, 320 × 844 | Variante vertical; três pares de badges; página/largura útil 305 px |
| GitHub Dark, todos os tamanhos | Não verificado: a sessão só permite Increase contrast; não há selector Dark nem emulação de cor disponível |
| Safari/iPhone físico e macOS | Não verificados; viewport não valida WebKit ou gestos reais |

Nos dois tamanhos mobile, as seis badges carregaram e mantiveram 20 px de altura.
O diagrama não força uma linha horizontal ilegível: passa a quatro etapas
verticais. Página e viewport útil mantêm a mesma largura. O Java conserva o
scroll horizontal do bloco, sem alargar a página. Os comprimentos do código
não foram reduzidos com abreviaturas de tipos ou exemplos fictícios.

A distância observada entre o título TUPREL e a secção do primeiro exemplo é
cerca de 479 px no desktop e 731 px a 320 px; o produto e o fluxo ficam claros
antes de percorrer duas alturas de ecrã. São medidas de layout desta observação,
não uma promessa para todos os browsers, fontes ou níveis de zoom.

Foram guardadas capturas reais anteriores (39fccd9) e da proposta, em desktop,
390 px e 320 px, assim como uma vista do Java a 320 px. A entrega no chat liga
essas capturas locais; não foram adicionadas ao repositório.
A apresentação foi aprovada pelo utilizador com as limitações aqui registadas.
Dark exige revisão real antes de se declarar validado; não foram alteradas
preferências do sistema para simular o teste.
O SVG horizontal da paleta escura foi inspeccionado no viewer de ficheiros
do GitHub em Light. Isso permite rever o asset, mas não valida o README em Dark.

## Gravação real da CLI — incorporação aprovada

Sessão Windows ConPTY, launcher tuprel.bat, prompt neutro, 88 × 26 células,
num projecto temporário novo. O processo recebeu apenas o ambiente necessário
ao Java/Windows e ao launcher, sem variáveis de configuração da base ou .env.
Foram capturados os bytes de saída do pseudo-terminal com timestamps monotónicos,
incluindo comandos introduzidos de uma vez, sem animação de digitação simulada.

Sequência efectivamente executada:

```text
tuprel --version
tuprel init --dry-run
tuprel init
tuprel validate
tuprel generate
tuprel generate --check
tuprel migrate create --name initial --dry-run
```

O comando echo %ERRORLEVEL% confirma 0 no final e também aparece na gravação.
Os outputs esperados de cada passo e os ficheiros gerados foram conferidos;
o dry-run não criou draft nem ficheiro de lock. Nenhum SQL ou JDBC foi executado.
O comando de versão devolve development, não uma versão publicada.

| Artefacto | Resultado |
|---|---|
| tuprel-cli-review.cast, local e ignorado pelo Git | Registo original, 4 345 bytes, com os timestamps capturados |
| docs/assets/readme/tuprel-cli-review.gif | Cópia exacta aprovada: 98 590 bytes (96,3 KiB), 693 × 491, 17 frames, 18,69 s |

O GIF foi renderizado do cast com [agg 1.9.0](https://github.com/asciinema/agg),
Consolas 14, tema github-dark, velocidade 1, limite de 10 fps e sem loop.
Não houve reconstrução manual de output. A configuração de cor do terminal
não equivale a uma validação do README no tema GitHub Dark.
O [pywinpty 3.1.2](https://pypi.org/project/pywinpty/) foi usado apenas no
recorder local; as ferramentas ficam em build/local-validation, sem dependência
nova no projecto nem instalação global. O binário oficial de agg foi conferido
contra o SHA-256 publicado no release.

As 17 imagens do GIF derivam apenas do fluxo de texto capturado. Todos os
17 frames e os metadados foram inspeccionados antes da incorporação; o
cast/transcript passou pela auditoria de segredos. A cópia versionada conserva
o SHA-256 do original. O README inclui alt descritivo, acesso ao tamanho
original e transcrição comparada com a captura. No mobile, a transcrição
legível complementa a animação; não se afirma leitura confortável de 88
colunas reduzidas a 320 px. O cast e as ferramentas continuam apenas locais.

SHA-256 do GIF: `a157d3934c5b6b8999180a4831543644e1545130b7d239e1c2b6e53242347e5b`.

## Honestidade e isolamento

Development Preview é visível no hero e na secção de compatibilidade. Não se
afirma publicação no Maven Central, instalador, adopção, cobertura ou desempenho.
History/status, deploy/dev/diff/drift, Starter, db pull, Inspector e Studio são
identificados como indisponíveis; só os comandos integrados aparecem nos exemplos.

Os PRs #28, #30 e #31 não recebem alterações, merges, retarget ou novas execuções
dos seus testes. Em particular, os dois testes locais de permissões do #28
continuam pendentes; a aprovação do check da main não os aprova. Nenhum dado
persistido do playground é escrito por esta entrega. O PR editorial tem
autorização explícita de integração após os checks do novo HEAD; os PRs
funcionais só avançam nas suas branches e com os seus próprios gates.
