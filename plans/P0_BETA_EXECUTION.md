# Execução P0 → P1

Baseline verificado em 6 de novembro de 2026. A `main` estava em
`48b08e61689cdc0678097b1419bf1b6f60b781b8`, com CI e CodeQL aprovados.
O repositório não tinha uma release oficial nem um website configurado.
A CLI é distribuída localmente pelo build e o playground usa um build
composto. O plano de fases continua a definir os contratos técnicos.
O P0 estabelece a ordem de entrega e os critérios da Beta pública.

| ID | Capacidade | Estado no baseline | Dependências e bloqueadores | Critério de aceitação | Trabalho paralelo e primeiro PR funcional |
|---|---|---|---|---|---|
| P0-01 | Motor de migrations | Em curso. Deploy legado, preview físico de dev, bootstrap interno e publicação nativa integrada | C3.1, prova de exclusão do legado C3.2, C3.3 e F6-006. Autorização operacional por alvo | Fluxo rever, publicar e aplicar em consumidor independente. PostgreSQL 17, concorrência, recuperação e recusa de drift ou evidência incerta | Via crítica. `feat/f6-005c3-physical-digest` fixa o digest e a identidade; seguir o [plano F6](PHASE_6_EXECUTION.md) |
| P0-02 | Distribuição e instalação | `installDist` e builds nativos verificáveis em CI. Sem release oficial | Canal de publicação, assinatura e chave de confiança. Integração do helper depende do cutover e da instalação controlada | Instalação, atualização, rollback do binário e desinstalação em Windows/Linux, fora do checkout. PATH explícito, verificação antes de instalar, release apenas após autorização | Independente de C3. Primeiro `build(cli): verify offline distribution bundles`, com pacote de teste, launchers, Java 21 e instalação em directório temporário. Avaliar runtime incluído e macOS por perfil |
| P0-03 | Spring Boot Starter | Planeado na Fase 7. Runtime já aceita `DataSource` | Decidir linha Spring Boot e contrato de auto-configuração isolado. Publicação depende de P0-05 | Aplicação independente com PostgreSQL, configuração validada, cliente tipado e ciclo de vida com o `DataSource` da aplicação | Independente de migrations e plugin. Primeiro `feat(spring): configure Tuprel with an application DataSource`; testar auto-configuração e override da aplicação com artefactos de teste |
| P0-04 | Gradle Plugin | Planeado na Fase 7. Geração invocada manualmente no playground | Contrato de plugin e versão mínima de Gradle. Publicação depende de coordenadas e repositório | TestKit comprova validate/generate, generated sources, execução incremental, cache seguro e preservação de código manual num projecto separado | Independente de Spring Boot e C3. Primeiro `feat(gradle): wire schema generation into Java compilation` |
| P0-05 | Maven e bibliotecas | Planeado na Fase 10. O build não configura publicação | Coordenadas e titularidade de domínio por confirmar para publicação. Contrato para Maven e alinhamento de versões | Consumidor Maven externo resolve bibliotecas, gera e compila fontes, testa dependências transitivas e atualização | Repositório Maven temporário e verificação de metadados podem avançar antes da publicação oficial. Primeiro `build(maven): verify staged libraries in an external consumer` |
| P0-06 | Projecto consumidor | Playground independente na execução, ligado ao monorepo por build composto | Fecho depende de artefactos instaláveis e P0-01. Variantes de integração dependem de P0-03/04/05 | Aplicação de vendas com fixtures isoladas e dados preservados em upgrade. Validar schema, geração, CRUD, relações, queries, transacções, migrations e recuperação | Modelo, fixtures e testes de contrato podem ser preparados cedo. Primeiro `test(consumer): verify a sales app with staged artifacts` |
| P0-07 | Segurança e qualidade | Verificação estrita de dependências, CodeQL Java/C e testes reais integrados | Cadeia de distribuição e cutover ainda incompletos. Política de atualização necessita de validação antes da Beta | Assinaturas verificadas, redacção, privilégios mínimos, recuperação exercitada, compatibilidade, política de segurança e checks aprovados | Trabalho transversal nos PRs funcionais. Primeiro `test(distribution): reject tampered bundles and manifests` junto à primeira via de instalação |
| P0-08 | Website e documentação pública | README e documentação pública no GitHub. Guia actual parte do código-fonte | Domínio, hospedagem e canal de distribuição. Guias de integração dependem dos contratos entregues | Homepage, instalação testada, pesquisa, referências CLI/Java, guias Spring/Maven/Gradle e exemplos reais. Validar navegação e downloads contra artefactos da Beta | Preparar estrutura e navegação a partir dos guias existentes. Primeiro `feat(website): build the public documentation and installation pages`, com preview e verificação de links |

## Sequência e gates

1. Manter P0-01 em execução. A C3.1 separa encoding, verificação de proveniência
   e revalidação no runner. C3.2 prova o corte com a versão anterior em
   fixtures. C3.3 liga a publicação nativa apenas em perfis validados.
2. Preparar P0-02, P0-03 e P0-04 em branches próprias e consumidores de
   teste. Os três podem usar artefactos locais e manter contratos isolados.
   Fixar versões e dependências externas nos PRs funcionais respectivos.
3. Validar P0-05 e P0-06 com artefactos fora do build composto. Fechar os
   critérios de distribuição, segurança, website e documentação.
4. A Beta requer aceitação de P0-01–08 para o âmbito anunciado, incluindo
   atualização, recuperação e matriz de compatibilidade explícita. Registar
   quaisquer bloqueios reais e obter aprovação da release antes de publicar.

O P1 fica após a avaliação Beta Ready. Introspecção e `db pull` (P1-01)
mantêm o contrato da Fase 8. JSONB, arrays e agregações (P1-02) e Query
Inspector opt-in (P1-03) seguem a Fase 9. Developer Experience avançada
(P1-04) e performance/compatibilidade alargada (P1-05) seguem o
[plano avançado](ADVANCED_DEVELOPER_EXPERIENCE.md) e a Fase 10. Studio e
integração opcional com IntelliJ mantêm os gates pós-1.0.
