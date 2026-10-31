# README Product Experience 2.0 — revisão

Observação: 2026-10-31. Base: main
`6f66ef47ddffb2c9e6d5e302cf2556536eebf472`. Alteração editorial independente,
na branch `docs/readme-product-experience`. A [página proposta](../../README.md)
documenta apenas o código integrado nessa base. Não altera runtime, builds,
schema, workflows, metadados do GitHub ou a proposta estratégica do PR #34.

A entrega inicial corresponde ao HEAD `39fccd9`. A revisão visual seguinte
mantém o mesmo PR #35 e todos os exemplos/anchors, acrescentando apenas o
workflow SVG e a reorganização do topo. A gravação CLI é preparada localmente
para aprovação separada; não é incorporada no README ou no Git.

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

| Antes | Proposta |
|---|---|
| Nome e frase introdutória alinhados à esquerda | Wordmark textual centrado, tagline própria e três entradas principais |
| Duas badges com estilos do GitHub | Seis badges Shields.io flat, 20 px, com rótulos acessíveis e quebra de linha |
| Um exemplo de query e preview juntos | Query em destaque; schema coerente, preview, relação e transacção com contexto |
| Capacidades numa lista geral | Benefícios concretos e três utilizações ligadas ao playground real |
| Tabela de navegação | Grupos Learn/Build/Look up e links junto do assunto |
| Instalação domina a leitura sequencial | Bash expandido, PowerShell e imports recolhíveis; schema disponível por expansão |

A hierarquia usa um H1, H2 por assunto e H3 para os três exemplos de comportamento.
Só se usa HTML suportado pelo GitHub: títulos/parágrafos, links/imagens,
anchors e details/summary. Sem CSS, layout em colunas, tabelas no README,
novos logótipos. A entrega inicial não tinha gráficos; a revisão visual propõe
um workflow SVG. Cores do texto, links e código ficam a cargo do tema.

## Badges e destinos

Todas usam Shields.io, `style=flat` e altura 20. As duas badges dinâmicas
consultam a branch main, não o HEAD deste PR. Os restantes rótulos descrevem
contratos existentes; não são resultados de testes.

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

- `check` da raiz e distribuição CLI aprovados com JDK 21 e verificação estrita
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
- Os 50 links internos do README, incluindo href HTML e fragments, resolvem
  com case exacto. Os 16 anchors anteriores, explícitos e de headings, foram
  preservados. Os nove destinos externos únicos do README responderam HTTP 200;
  os seis SVGs continham os rótulos esperados, incluindo CI/CodeQL passing.

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

## Revisão visual

- GitHub Light: hero, badges, títulos e exemplos inspeccionados no renderer real,
  tanto na vista de ficheiro como no README da página da branch.
- Viewport 390 × 844: hero legível, seis badges carregadas e quebra de linha
  correcta. Largura útil de 375 px com scrollbar; página e viewport útil tinham
  a mesma largura. Expandir PowerShell e schema funcionou; blocos longos usam
  scroll horizontal próprio, sem alargar a página.
- Dark: pendente. A sessão GitHub sem autenticação só expõe Increase contrast,
  não um selector de tema. Não foram alteradas preferências do sistema nem
  simulada uma aprovação visual de Dark.
- iPhone físico/Safari e macOS: não exercitados. A viewport comprova layout
  nessa dimensão, não compatibilidade com o motor WebKit ou gestos de um aparelho.

Preview: abrir a branch no GitHub, seleccionar README e testar os detalhes
recolhíveis. A vista de ficheiro oferece uma leitura sem o inventário da raiz.
Para aprovação final, confirmar também Dark e Safari num iPhone; não depender
apenas das capturas locais em Light.

## Proposta de demonstração animada — por aprovar

Propor uma gravação de 25–40 segundos de uma execução offline real, terminal
80 × 24 ou 100 × 28, fonte legível, sem música, efeitos ou texto de resultados
reconstruído. Esta entrega não adiciona animação, imagem, gravador ou asset ao repo.

Preparar antecipadamente o launcher da mesma revisão, uma pasta temporária
vazia e um prompt neutro que não mostre o nome/caminho do utilizador. Não
carregar .env, imprimir ambiente nem abrir ligação de rede/base durante a cena.
Gravar a seguinte sequência num único ambiente Bash ou PowerShell, usando
apenas o launcher já construído:

```text
tuprel --version
tuprel init --dry-run
tuprel init
tuprel validate
tuprel generate
tuprel generate --check
tuprel migrate create --name initial --dry-run
```

Os comandos foram efectivamente exercitados nesta entrega. Entre as linhas
capturadas, sem reconstruir timings ou output:

```text
tuprel development
Schema is valid.
```

Mostrar também, na captura futura, os ficheiros realmente gerados e a indicação
de que o último comando não escreveu nem reservou um ID. A versão development
não é uma release. Não mostrar deploy, status ou outros comandos de branches draft.

Antes de adicionar o asset: aprovar ferramenta/licença/formato, fazer uma nova
gravação real, rever todos os frames quanto a credenciais, paths e notificações,
e comparar Light/Dark/mobile. Preferir GIF optimizado com link para transcript
textual e imagem estática, sujeito a orçamento de tamanho aprovado. Não publicar
uma animação fabricada a partir dos logs ou uma reprodução artificial da digitação.

## Honestidade e isolamento

Development Preview é visível no hero e na secção de compatibilidade. Não se
afirma publicação no Maven Central, instalador, adopção, cobertura ou desempenho.
History/status, deploy/dev/diff/drift, Starter, db pull, Inspector e Studio são
identificados como indisponíveis; só os comandos integrados aparecem nos exemplos.

Os PRs #28, #30 e #31 não recebem alterações, merges, retarget ou novas execuções
dos seus testes. Em particular, os dois testes locais de permissões do #28
continuam pendentes; a aprovação do check da main não os aprova. Nenhum dado
persistido do playground é escrito por esta entrega. O PR editorial fica para
revisão explícita, sem auto-merge.
