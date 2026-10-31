# Experiência pública — auditoria e proposta

Observação inicial: 2026-10-31, main `8f5e0ce047c81fd0adcc3088c7bc54dd31211542`.
Âmbito editorial, sem alterações de runtime, configurações de build ou base de dados.
Esta proposta precede a reorganização da navegação; os caminhos existentes mantêm-se.

## Auditoria anterior

| Área | Observação | Resposta proposta |
|---|---|---|
| Posicionamento | README extenso explica fases e implementação antes da utilização | Proposta de valor concreta, exemplo do playground e quickstart offline |
| Exemplo | Schema User/Post seguido de Java Customer/Note/Product sem percurso comum | Referenciar schema e cliente reais do playground nos exemplos Java |
| Documentação | docs/ sem README; mistura especificações, pesquisas e relatórios | Índice público por tarefa e entrada própria para contribuidores |
| Idioma | Guias essenciais em português; comandos dependentes da máquina original | Guias públicos em inglês; planos/decisões internos mantêm português |
| Estado | CLI_EXPERIENCE chama F6-002A «em revisão»; BUILD_AND_TEST lista seis módulos, main tem sete | Corrigir factos sem antecipar branches draft |
| Segurança | SECURITY diz que falta activar reporte privado; API confirma enabled=true | Corrigir canal existente, sem inventar email ou SLA |
| Organização | Só tuprel/tuprel público, sem perfil; tuprel/.github devolve 404 autenticado | Preparar profile/README.md num PR separado, sem activar antes da revisão |
| Metadados | Homepage ausente, topics vazios, Issues activo, Discussions inactivo; sem releases | Propostas sem aplicar configurações públicas nesta tarefa |
| Identidade | Não são necessários gráficos para explicar o produto | Markdown nativo, duas badges funcionais da main; sem assets novos |

Fontes públicas: [repositório](https://github.com/tuprel/tuprel) e
[organização](https://github.com/tuprel). Configurações confirmadas por API autenticada.
Acesso de proprietário confirmado na organização e no repositório; é possível
preparar o repositório de perfil sem depender de intervenção administrativa.

## Arquitectura de informação proposta

- README.md: apresentação, exemplo, quickstart e portas de entrada.
- docs/README.md: hub público, disponibilidade actual e percurso de aprendizagem.
- docs/getting-started/: instalação a partir do source e quickstart sem DB.
- docs/guides/: schema, cliente Java, CRUD/queries, relações, transacções e migrations.
- docs/reference/: CLI implementada e acesso à JavaDoc real gerada localmente.
- docs/help/: troubleshooting e FAQ.
- CONTRIBUTING.md e SECURITY.md: políticas públicas existentes, em inglês.
- examples/tuprel-playground/README.md: aplicação real, setup e efeitos explícitos.
- docs/architecture, docs/adr, docs/rfcs, docs/development, docs/testing,
  docs/security, docs/research, docs/product e plans/: engenharia e visão,
  acessíveis para contribuidores; não são um manual de funcionalidades disponíveis.

Não mover AGENTS.md, CLAUDE.md, START_HERE.md, CLAUDE_FIRST_PROMPT.md,
HANDOFF_CHECKLIST.md ou FILE_INVENTORY.md. AGENTS/CLAUDE referenciam START_HERE;
START_HERE/HANDOFF referenciam CLAUDE_FIRST_PROMPT. Paths de ADRs, RFCs e planos
alimentam outros documentos. Um movimento cosmético quebraria esses contratos.
Workflows, scripts, classes, schemas e configuração local não mudam.

## Convenção editorial e visual

Um H1 por página, introdução curta, H2 por tarefa e tabelas para comparações.
Links relativos no monorepo, absolutos entre repositórios. Guias começam com
ligação ao índice. Java usa tipos explícitos e identifica as variáveis fornecidas.
Nenhum output de terminal simulado; capturas só de execução real e sem valores locais.

GitHub escolhe tipografia, contraste e realce em Light/Dark. Sem HTML de layout,
cores fixas, imagens geradas, novos logótipos ou GIFs. Badges CI/CodeQL mostram a
main, não o PR. Qualquer asset futuro requer proposta e aprovação separadas.

## Metadados para aprovação, sem aplicar nesta tarefa

| Superfície | Sugestão |
|---|---|
| Descrição do repo/organização | Schema-defined Java models and typed PostgreSQL clients. Explicit queries, relations and transactions. |
| Website | https://github.com/tuprel/tuprel/tree/main/docs — só após integrar a documentação |
| Topics do repo | java, postgresql, orm, jdbc, code-generation, type-safe, database, cli |
| Pins públicos | Apenas tuprel/tuprel; .github é infraestrutura, não outro produto |

Topics pertencem a repositórios; não inventar uma configuração independente da
organização. Não activar Discussions, Pages ou releases. Não prometer integração
Spring, instalador, benchmarks ou macOS testado. Coordenadas Maven são provisórias.

## Critérios de aceitação

1. Paths antigos preservados; links novos e anchors resolvem com case correcto.
2. Schemas aceites pela CLI real; Java compila com o cliente real gerado,
   JDK 21 e -Xlint:all -Werror.
3. Quickstart em pastas novas, sem JDBC; Bash/PowerShell reportados separadamente.
4. Verificações offline, JavaDoc e Spotless; build do playground sequencial.
   Sem run/bootstrap ou integração PostgreSQL local nesta tarefa editorial.
   CI mantém testes PostgreSQL e quality gates completos.
5. #28 continua draft, mesmo HEAD; dois testes locais de role continuam pendentes.
6. CI dos PRs verificada no HEAD actual; nenhum merge automático.
7. Perfil fica fora da default branch até revisão; metadados propostos por aplicar.

## Perfil da organização e aplicação pelo proprietário

Foi criado tuprel/.github público, com README inicial neutro. O perfil proposto
fica exclusivamente em docs/organization-profile; profile/README.md não existe
na main. A criação do repo não activa esse ficheiro como perfil público.
Não foram acrescentados community health defaults, workflows ou políticas globais.

Após revisão e aprovação explícita:

1. Integrar primeiro o PR editorial de tuprel/tuprel, para que a pasta docs da
   main apresente o novo índice. Rever README, guides e exemplos na vista renderizada.
2. Rever o PR de tuprel/.github e só depois integrar profile/README.md. A
   [documentação oficial](https://docs.github.com/en/organizations/collaborating-with-groups-in-organizations/customizing-your-organizations-profile)
   explica a publicação a partir desse path na default branch.
3. No About de tuprel/tuprel, abrir a engrenagem e aplicar descrição, Website
   e topics aprovados da tabela acima. Os
   [topics são metadados do repo](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/classifying-your-repository-with-topics).
4. Como proprietário, abrir github.com/tuprel, seleccionar a vista Public e
   Customize pins (ou pin repositories); fixar apenas tuprel/tuprel. A descrição
   e Website da organização editam-se nas Settings do perfil. Não criar produtos
   adicionais nem apontar para um domínio/site que ainda não existe.
5. Inspeccionar GitHub Light e Dark com o menu de aparência. Confirmar README,
   tabelas, blocos de código, detalhes recolhíveis e navegação entre páginas.

As alterações de metadados e ambos os merges ficam por aplicar nesta entrega.
Os PRs e links de preview exactos acompanham o relatório da entrega.

## Validação da entrega

- test, javadoc, spotlessCheck, :tuprel-cli:installDist e :build-logic:check
  aprovados: 215 casos unitários aprovados e quatro skips de symlink Windows,
  mantidos activos/obrigatórios em Linux. Tarefas sem alterações reutilizadas.
  Nenhuma integrationTest local
  foi seleccionada. JavaDoc gerou avisos existentes de documentação em fontes
  inalterados; não é uma execução JavaDoc warning-free.
- Build do playground e os seus dois testes aprovados, depois da raiz, sem run,
  checkConnection, bootstrap ou acesso JDBC nesta tarefa.
- 12 fragmentos Java públicos compilados com javac 21, -Xlint:all -Werror;
  schema do guia validado/gerado/verificado e Java resultante compilado.
- README e quickstart executados com launchers reais PowerShell e Git Bash,
  em quatro projectos temporários novos. Os passos de clone/build já satisfeitos
  pelo checkout foram omitidos nesses smoke tests; o build real correu antes.
  Init, validação, geração/check e criação/dry-run offline passaram. Git Bash
  local não é uma execução macOS; Linux/Bash também é verificado pela CI existente.
- 135 links internos e anchors verificados com case exacto; anchors de entrada
  antigos do README e das políticas traduzidas preservados. Nenhum path removido.
- 15 URLs externas únicas verificadas por HTTP, todas com resposta 200, incluindo
  os links do perfil, políticas, workflows e badges. Links de preview das branches
  são verificados depois do push. CI, auditoria dos commits e estado final do #28
  são conferidos antes do relatório, sem converter pendências em aprovação.

Reprodução das verificações existentes (PowerShell, JDK 21 activo):

```powershell
.\gradlew.bat test javadoc spotlessCheck :tuprel-cli:installDist :build-logic:check --dependency-verification strict
.\gradlew.bat -p examples/tuprel-playground build --dependency-verification strict
```

Para repetir a conferência dos fragmentos, gerar/compilar o playground, copiar
cada fence Java para um método com os imports/variáveis indicados no guia e usar
javac --release 21 -Xlint:all -Werror com o seu classpath. Os fragmentos foram
compilados, não executados contra uma base. O schema pode ser conferido com o
launcher validate/generate/generate --check num directório novo.

Riscos residuais: avisos JavaDoc nos fontes, ausência de release estável e
validação macOS pendente. O PR #28 e as propostas de execução de migrations
continuam fora do código documentado como disponível na main.
