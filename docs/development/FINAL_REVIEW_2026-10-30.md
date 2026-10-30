# Revisão final — relações, transacções e playground

Data: 2026-10-30. Branch: `codex/local-postgresql-playground`.
Base de revisão: PR #11, `feat/relations-transactions`, head `ce07dfa`.
Estado inicial desta etapa: `2b9ef01`, working tree limpa.

## Resultado da revisão

A arquitectura e os contratos da Fase 5 foram preservados. Não há novas
dependências de produto, ciclos, JPA/Hibernate, escrita implícita de grafos ou
SQL formado a partir de valores externos. Código gerado continua a ser produzido
pela CLI e validado por compilação; não foi editado manualmente.

Foram corrigidos os problemas abaixo. A recomendação de integração pressupõe
aprovação da CI no head actual e revisão humana. O estado remoto e os links
dos checks são comunicados no PR; este relatório não substitui esses checks.
Não constitui readiness da release 1.0 nem aprovação da Fase 6.

## Problemas encontrados e correcções

| Prioridade | Evidência/cenário | Correcção e validação |
|---|---|---|
| P1 | PostgreSQL aborta uma transacção após violação de unicidade; a aplicação captura o erro e retorna normalmente. O antigo commit podia resultar em rollback no servidor, sem o runtime comunicar falha | Marcar falha de execução/fetch no scope actual; recusar conclusão normal com `25000`. Regressão PostgreSQL confirma ausência de escrita parcial e falha explícita |
| P1 | Capturar erro SQL dentro de savepoint não podia invalidar toda a transacção exterior nem produzir sucesso falso do bloco interior | Verificar o scope filho, reverter/libertar o savepoint e deixar o pai continuar se o chamador tratar a falha. Regressão real confirma apenas as linhas exteriores |
| P1 | Excepções não verificadas de proxies/pools em commit ou restauro podiam saltar o close | Commit percorre rollback/restauro/close; restauro falhado aborta e fecha a ligação. Testes preservam a causa e suppressed failures |
| P2 | `ResultSet.close()` não verificado podia impedir o fecho do statement; um `Error` na abertura de stream escapava antes do registo no scope | Fechar ambos os recursos, agregar falhas e fechar statements ainda não registados. Regressões de lifecycle |
| P2 | Falha de criação de savepoint capturada pelo chamador não invalidava a conclusão exterior | Marcar os pais apenas para rollback; teste comprova que não ocorre commit |
| P2 | O prazo transaccional só era aplicado a statements e podia expirar antes de commit | Verificar no fim do callback e imediatamente antes de commit. Teste com relógio controlado, incluindo overflow de `nanoTime`, sem sleeps novos |
| P1/CI | CodeQL executava `build` no modo local, sem credenciais no runner | Seleccionar explicitamente `-PtuprelTestDatabase=container`, como a CI principal; compilar também o exemplo para análise |
| P1/CI | Workflows filtrados apenas para PRs destinados a `main` não cobririam um PR encadeado no #11 | CI, CodeQL e Dependency Review aceitam também `feat/relations-transactions` |
| P2/CI | O teste de symlinks poderia ser ignorado silenciosamente num runner Linux | Gate no XML JUnit exige que o caso exista, execute e passe. A assumption Windows mantém-se |

As assinaturas públicas foram preservadas. O contrato passa a explicitar que
capturar uma falha JDBC de execução não torna o bloco apto para commit; para
recuperação parcial, usar savepoint e tratar a falha do bloco aninhado.
Uma falha de fecho depois de commit não desfaz esse commit e não autoriza retry
cego. Estes comportamentos estão descritos no RFC-005.

## Testes e isolamento PostgreSQL

Validação local em Java 21, PostgreSQL 17.11, apenas `tuprel_devtest`:

- Build raiz: `gradlew.bat build --rerun-tasks --no-daemon
  --dependency-verification strict -PtuprelTestDatabase=local` — aprovada,
  56 tasks executadas, incluindo `check` e os seis testes reais de TestKit.
- Build do exemplo: `gradlew.bat -p examples/tuprel-playground build
  --rerun-tasks --no-daemon --dependency-verification strict` — aprovada,
  31 tasks executadas e dois testes do consumidor aprovados.
- Totais dos relatórios: **163 aprovados, zero falhas, um ignorado** no Windows.
  Incluem **25 testes PostgreSQL**, 25 testes de lifecycle transaccional e
  compilation/golden/security tests das funcionalidades anteriores.
- O único skip continua a ser o teste de symlinks sem privilégios no Windows.
  No Linux, a CI exige execução efectiva desse mesmo teste, sem o desactivar.

Os dois rebuilds foram inicialmente lançados em paralelo e disputaram outputs
do composite build, causando um erro de accessors Kotlin na raiz. A execução
sequencial completa passou; nenhum quality gate ou dependência foi alterado
para contornar essa falha. As tarefas partilhadas devem correr sequencialmente.

Mantêm-se as três suites e todos os cenários anteriores. Uma nova regressão
confirma que duas connections diferentes têm `current_schema()` e `search_path`
exactamente iguais ao schema privado `tuprel_it_<UUID>`, sem fallback para public.
O catálogo é verificado antes de DDL/limpeza; apenas o schema criado pela fixture
é eliminado. O nome é gerado internamente e não provém de input do utilizador.
Não há chamadas Docker no modo local; o modo container só existe por opção.

## Segredos, logs e supply chain

O `.env` do playground permanece ignorado pelo Git. Só os programas Java o
carregam, conforme pedido do utilizador; o agente não apresenta o seu conteúdo.
Não há passwords em argumentos Gradle, propriedades de configuração ou templates.
`.env.example` contém uma password vazia. O loader só aceita as três chaves
documentadas e não executa shell/interpolação; erros não incluem valores.

Foi executada uma auditoria automática local dos commits desde `origin/main`:
nomes de ficheiros sensíveis, assinatura de tokens/chaves e comparação com a
credencial local conhecida, sem a imprimir. Foram igualmente verificados os
relatórios/logs de texto disponíveis nas pastas de build. O resumo numérico é
registado na entrega/PR depois de repetir a auditoria no head a publicar.
O âmbito é o histórico novo e esses logs locais; não se afirma cobrir segredos
desconhecidos em sistemas externos ou confirmar ausência de todos os alertas.

Driver 42.7.14, lockfiles e verificação SHA-256 mantidos. Catálogos de verificação
da raiz e exemplo iguais, com comparação obrigatória na CI. Actions continuam
fixadas por SHA e permissões mínimas; não se adicionaram tokens ou novas actions.

## Playground e âmbito

O playground é uma aplicação Gradle consumidora num composite build porque
a biblioteca ainda não está publicada. Compila fontes gerados e usa a API
pública real. Pode ser aberto isoladamente no IntelliJ dentro deste checkout;
não se promete que copiar apenas a pasta funcione sem os módulos referenciados.
O suporte de `.env` é compilado apenas por testes/exemplo, fora dos JARs de produto.

As cópias de Wrapper e verification metadata são deliberadas para o projecto
consumidor independente; a CI detecta divergência do catálogo. Não encontrei
refactorings de produto fora do âmbito ou dependências pesadas novas.
O relatório histórico da primeira entrega mantém-se, identificado como tal.

O DDL manual é estático e limitado a `tuprel_playground`. Não é migration,
não corrige drift e não altera outras bases. Os dados sintéticos do exemplo
são conservados. A ligação JDBC e o percurso completo são repetidos na entrega
final, além dos testes automatizados acima.

## Estratégia Git e integração

1. Preservado o head inicial na branch local
   `codex/backup-playground-before-final-review-20261030`, em `2b9ef01`.
2. Manter todos os commits da primeira etapa, acrescentando correcções coesas.
   Sem rebase, force push, cherry-pick duplicado ou alteração de `main`.
3. Publicar `codex/local-postgresql-playground` e criar PR com base
   `feat/relations-transactions`. O diff mostra apenas a consolidação sobre o #11.
4. Quando autorizado, a integração preferida é este PR na branch do #11,
   seguida da revisão/integração do #11 em `main`. Nenhum desses merges é automático.
5. Se o #11 for integrado primeiro por squash, preservar a backup e planear o
   retarget/rebase com revisão do novo merge-base; não executar essa operação
   silenciosamente. Aprovar um PR não implica aprovar o outro.

O PR do driver (#15) contém uma actualização já contemplada nesta branch; rever
a sua necessidade após a integração para não repetir alterações de lock/checksum.

## Riscos e limites que permanecem

- Isolamento PostgreSQL fora do subconjunto testado e outros pools/drivers não
  têm uma certificação universal. Abort e close também podem falhar; as causas
  são preservadas e a recuperação absoluta de ligações avariadas não é prometida.
- Deadlines não interrompem callbacks Java ou commits já iniciados; os timeouts
  de statement são arredondados a segundos. Não há retry automático.
- Includes fora de transacção não dão snapshot único. Deletes não verificam
  versão nesta fase. Estes limites são deliberados e documentados.
- API ainda não estabilizada para 1.0 e migrations inexistentes. A proposta
  RFC-006 precisa de aprovação explícita; não se iniciou implementação.
- O teste de symlinks deve ser confirmado no runner Linux do PR publicado.

Sem blockers locais conhecidos depois das correcções. **Pronto para integração
na branch do #11 quando a CI do head publicado estiver aprovada e a revisão
humana terminar**, sem autorizar merge ou publicação por este relatório.
