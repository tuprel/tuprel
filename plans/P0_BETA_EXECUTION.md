# Execução P0 → P1

Baseline verificado em 6 de novembro de 2026. A `main` estava em
`48b08e61689cdc0678097b1419bf1b6f60b781b8`, com CI e CodeQL aprovados.
O repositório não tinha uma release oficial. O frontend público está no repositório [tuprel/tuprel-website](https://github.com/tuprel/tuprel-website).
A CLI é distribuída localmente pelo build e o playground usa um build
composto. O plano de fases continua a definir os contratos técnicos.
O P0 estabelece a ordem de entrega e os critérios da Beta pública.

| ID | Capacidade | Estado | Dependências e bloqueadores | Critério de aceitação | Trabalho paralelo e primeiro PR funcional |
|---|---|---|---|---|---|
| P0-01 | Motor de migrations | C3.3 comprovada em staging num consumidor independente, com PostgreSQL 17 e ext4 | F6-006 integrada pelos [#72](https://github.com/tuprel/tuprel/pull/72) e [#82](https://github.com/tuprel/tuprel/pull/82). Autorização operacional e cutover por alvo | Fluxo de revisão, publicação e deploy com provas de concorrência, recuperação e recusa de drift ou evidência incerta | Via crítica. Consultar a validação e os limites no [plano F6](PHASE_6_EXECUTION.md) |
| P0-02 | Distribuição e instalação | O [#76](https://github.com/tuprel/tuprel/pull/76) integra em `57b1be6` a instalação assinada, mudança de versão e remoção em staging Windows e Linux. O [#80](https://github.com/tuprel/tuprel/pull/80), `48be6c1`, prova a verificação com chave externa. O [#84](https://github.com/tuprel/tuprel/pull/84), `9891d48`, acrescenta instalação idempotente e remoção recuperável em staging Windows e Linux. O [#88](https://github.com/tuprel/tuprel/pull/88) reforça o confinamento na extracção | Canal de publicação e âncora de confiança, ACLs e identidade, PATH persistente, selecção de versão e durabilidade após falha de energia. O ADR-0022 foi aceite para implementação e testes. A P0-02A inclui uma prova Linux de identidade e escrita relativa ao descritor, com validação Windows ainda pendente. Escrita gerida depende do cutover por alvo | Instalação, atualização, rollback do binário e desinstalação em Windows/Linux, fora do checkout. PATH explícito, verificação antes de instalar, release apenas após autorização | Chave efémera e PATH de sessão nas provas. Imagem Java 21 de 48,51 MiB validada localmente em Windows com assinatura Ed25519, instalação, comandos offline e remoção. Linux, JDBC/TLS e actualização do runtime por validar |
| P0-03 | Spring Boot Starter | Starter no build de desenvolvimento. O [#77](https://github.com/tuprel/tuprel/pull/77) testa Spring Boot 4.1.2 e PostgreSQL 17 numa aplicação externa. O [#87](https://github.com/tuprel/tuprel/pull/87) prova a recusa de ownership implícito; ADR-0023 proposto | Matriz de versões Spring Boot, contrato de participação em transacções Spring e publicação, dependente de P0-05 | Aplicação independente com PostgreSQL, configuração validada, cliente tipado e ciclo de vida com o `DataSource` da aplicação | Prova de auto-configuração, precedência explícita, ciclo de vida do DataSource e transacção PostgreSQL |
| P0-04 | Gradle Plugin | Plugin de desenvolvimento integrado pelo [#75](https://github.com/tuprel/tuprel/pull/75) em `ccf1b30`. O [#81](https://github.com/tuprel/tuprel/pull/81), `16da431`, valida Gradle 9.7.1 e 9.8.2 em projecto independente com cache global activo. O [#86](https://github.com/tuprel/tuprel/pull/86) valida o marcador do plugin num repositório Maven temporário | Distribuição oficial e compatibilidade fora das versões verificadas | TestKit comprova validate/generate, generated sources, execução incremental, cache seguro e preservação de código manual num projecto separado | Validação, geração, compilação e outputs incrementais comprovados com a CLI de staging. A restauração do cache fica desactivada por omissão |
| P0-05 | Maven e bibliotecas | O [#79](https://github.com/tuprel/tuprel/pull/79), `606f48e`, prova em Windows e Linux o upgrade Maven entre duas revisões em staging, incluindo binários e fontes geradas. A prova de release verifica localmente POMs, JARs e metadata com SHA-256 e assinatura OpenPGP efémera | Coordenadas definitivas, titularidade de domínio, chave oficial e upload autenticado ao Central Portal | Consumidor Maven externo resolve bibliotecas, gera e compila fontes, testa dependências transitivas e atualização | [#71](https://github.com/tuprel/tuprel/pull/71) verifica POM e scopes. [#73](https://github.com/tuprel/tuprel/pull/73) integra compilação e execução Maven externas em `cd47d83` |
| P0-06 | Projecto consumidor | Aplicação de vendas em staging valida geração, CRUD, queries, relações, transacções e migrations a partir dos artefactos instalados | Instalação distribuível e variantes de integração P0-03/04/05 | Validar schema, geração, CRUD, relações, queries, transacções, migrations, preservação de dados em upgrade e recuperação com artefactos distribuíveis | [#69](https://github.com/tuprel/tuprel/pull/69) prova o runtime fora do checkout. [#70](https://github.com/tuprel/tuprel/pull/70) prova o fluxo de migrations e a preservação dos dados durante o upgrade |
| P0-07 | Segurança e qualidade | Verificação estrita de dependências, CodeQL Java/C e testes reais integrados | Cadeia de distribuição e cutover ainda incompletos. Política de atualização necessita de validação antes da Beta | Assinaturas verificadas, redacção, privilégios mínimos, recuperação exercitada, compatibilidade, política de segurança e checks aprovados | Trabalho transversal nos PRs funcionais. Primeiro `test(distribution): reject tampered bundles and manifests` junto à primeira via de instalação |
| P0-08 | Website e documentação pública | Website independente Next.js com navegação, pesquisa e CI próprios. O [#1](https://github.com/tuprel/tuprel-website/pull/1) sincroniza os guias Spring, Gradle e Maven. A instalação documentada parte do código-fonte | Domínio, hospedagem e canal de distribuição. Guias de integração dependem dos contratos entregues | Homepage, instalação testada, pesquisa, referências CLI/Java, guias Spring/Maven/Gradle e exemplos reais. Validar navegação e downloads contra artefactos da Beta | O repositório Java mantém as fontes técnicas. O website importa um commit fixado e valida navegação e exemplos. Domínio e downloads da Beta pendentes |

## Sequência e gates

1. Consolidar P0-01 na F6-006. A C3.3 prova o fluxo gerido em staging no
   perfil Linux x86-64 com ext4. A activação operacional continua sujeita
   à validação do procedimento de transição para cada alvo.
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
