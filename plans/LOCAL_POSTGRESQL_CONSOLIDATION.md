# Consolidação local — primeira entrega

Base: `origin/feat/relations-transactions` (`ce07dfa`), PR #11 aberto.
`main` (`cd406e6`) contém as Fases 1–4. Esta branch não implica merge do PR.

## Âmbito e critérios de aceitação

1. Rever transacções, recursos JDBC, includes e batch existentes; corrigir
   failure paths com testes de regressão, sem redesenhar a API pública.
2. Executar as três suites PostgreSQL existentes contra uma base local dedicada
   `tuprel_devtest`, configurada por ambiente, sem fallback para Docker.
   Isolar cada suite num schema aleatório; limpar apenas o schema criado pela suite.
   Testcontainers mantém-se disponível por opção explícita na CI.
3. Criar `examples/tuprel-playground` como aplicação Gradle independente, Java 21,
   consumindo os módulos reais por composite build. Gerar o cliente pela CLI.
   Exercitar clientes, produtos, encomendas e itens, CRUD, queries, includes,
   commit, rollback, savepoints, batch e erros esperados.
4. Usar apenas SQL DDL estático e revisto para o bootstrap do exemplo. Deixar
   explícito que migrations ainda não existem; conservar os dados do exemplo.
5. Actualizar o driver JDBC de segurança, lockfiles e verification metadata;
   verificar a origem dos checksums sem desactivar dependency verification.
6. Executar unitários, `check`, compilação do exemplo e integração quando o
   ambiente permitir. Registar falhas de infra-estrutura e testes não executados.

Não inclui migrations, merge, push ou publicação. Migrations exigem desenho
e aprovação posteriores. O agente não inspecciona conteúdos de `.env` ou
passwords. Por pedido posterior do utilizador, a aplicação e os testes podem
carregar o `.env` local do playground, ignorado pelo Git.

## Segurança e compatibilidade

O modo local aceita apenas URL sem parâmetros/credenciais, host loopback,
porta explícita e nome exacto da base dedicada. Confirmar também o catálogo
da connection antes de DDL. As credenciais vêm do ambiente ou do `.env` do
playground; o ambiente não vazio tem precedência. O ficheiro é carregado apenas
no processo Java, nunca pela configuração Gradle ou por logs do agente.
DDL de limpeza limita-se ao schema gerado internamente. O exemplo usa um
schema próprio e não apaga dados de execuções anteriores.

As alterações do runtime preservam assinaturas públicas e reforçam atomicidade
em failure paths. O grafo de módulos de produto mantém-se.

## Validação concluída

PostgreSQL 17.11 local, porta 5432 e base `tuprel_devtest`, configurados pelo
`.env` do playground. Ligação JDBC, `check` completo, 22 testes de integração
e duas execuções do playground aprovados. Confirmados 2 clientes, 4 produtos,
2 encomendas e 4 itens persistidos, sem schemas de testes remanescentes.
154 testes aprovados no total; um teste de symlinks ignorado no Windows.
Sem Docker local, push, merge ou implementação de migrations.
