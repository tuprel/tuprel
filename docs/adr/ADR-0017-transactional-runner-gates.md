# ADR-0017 — Gates propostos para execução transaccional

Estado: **proposta para aprovação específica antes de implementar F6-003B**.
Não há executor, advisory locks ou DDL implementados neste PR. Desenvolvido como
contrato de segurança complementar aos ADRs 0014/0015/0016; não inventar consenso.

## Problema e restrições

Gramática conhecida/digest não provam que o estado físico admite uma operação.
Uma tabela com herança/partições pode propagar DDL para outro schema; um index
sobre tipo/opclass customizado pode executar funções não presentes no source.
É necessário decidir estes gates **antes** do primeiro runner, mesmo que drift
completo permaneça na F6-005. Preservar dados, Java 21 e schemas isolados locais.

## Decisão proposta para revisão

1. Uma connection dedicada e idle durante o deploy, não transaction-pooling.
   Verificar database/user/schema reais contra a configuração aprovada. Target
   fingerprint inclui URL validado, schema, principal e TLS; password excluída.
   Nunca usar target/snapshot fornecido pelo chamador como prova do estado vivo.
2. Advisory lock **de sessão** do ADR-0014 antes de observar/criar histórico.
   Concretizar chave: primeiros 8 bytes de SHA-256, interpretados int64 big-endian,
   de S("TUPREL-PG-MIGRATION-LOCK"), I32(1), S(schema), com encoding ADR-0016.
   Não alterar este namespace entre versões sem transição aprovada. Colisão
   teórica serializa targets diferentes; locks pertencem à DB/session.
3. Prazo monotónico de aquisição de 30s; polling limitado e interruptível, sem
   retries de SQL de migrations. Concretizar intervalo 100ms. Locks de DDL:
   lock_timeout 10s e statement_timeout 20s por transacção, com diagnóstico seguro.
   Socket timeout 30s mantém margem sobre o statement timeout; não inverter esses prazos.
   Limites inicialmente fixos; qualquer configuração futura terá validação e
   máximos finitos, não opções URL arbitrárias. Timeouts não provam resultado do commit.
4. Sob lock: voltar a descobrir ficheiros v1, validar histórico conhecido em
   snapshot consistente e compilar novamente todo o plano. Comparar o digest
   explicitamente aprovado com o novo. BLOCKED, NO_CHANGES e divergência nunca
   desencadeiam DDL. Não existe --yes que aprove unknown/destructive.
5. Antes de DDL em relações existentes, adquirir lock de relação que impeça
   alteração da sua definição e validar metadados **na mesma transacção**.
   ACCESS EXCLUSIVE para ADD COLUMN e SHARE para index normal, em ordem
   determinística, escolhendo por tabela o modo mais forte necessário na migration.
   São os modos nativos destas operações: SHARE permite SELECT e bloqueia writes;
   ACCESS EXCLUSIVE também bloqueia reads. Não rebaixar nem ocultar estes custos.
   Usar LOCK TABLE ONLY para não propagar locks a descendentes antes de recusar
   herança/partições; verificar kind antes e identidade/OID depois do lock.
6. Só tabelas persistentes ordinárias, no schema explícito, sem herança/partições,
   RLS, regras ou triggers customizados. Para index, colunas simples de tipos
   built-in admitidos, collation/default opclass conhecidos do pg_catalog; rejeitar
   estruturas desconhecidas, não inferir segurança. Revalidar identidade da relação
   após o lock, evitando troca de nome/OID entre lookup e leitura.
7. Recusar event triggers DDL activos customizados no primeiro subconjunto;
   não os desactivar. Não prometer sandbox contra DBA malicioso ou DDL administrativo
   concorrente. Exigir ACLs/ownership e janela operacional sem essas alterações.
8. CREATE TABLE exige ausência do nome; nunca IF NOT EXISTS, drop/recreate ou
   reparação automática. Uma corrida com ferramenta externa falha/rollback,
   sem retry. Novos objetos devem permanecer no target, com tipos explícitos
   pg_catalog e SQL canónico já vinculado ao digest, sem reler source raw.

## Histórico e unidade transaccional

Histórico v1 tem exactamente a estrutura reconhecida pelo #28/ADR-0014: nove
colunas NOT NULL, PK migration_id e quatro CHECKs conhecidos. Extrair uma definição
canónica partilhada entre validador e bootstrap para evitar divergências.
Criação interna do histórico é SQL confiável da biblioteca, separado dos scripts
do utilizador (a gramática destes não admite CHECKs). Schema tem de preexistir;
nunca criar schemas ou adoptar uma tabela desconhecida automaticamente.

Verificar plano/aprovação antes de bootstrap. Se necessário, criar histórico
numa transacção própria sob lock; cada migration subsequente tem uma transacção
própria com DDL + INSERT APPLIED atómicos. Registar hash dos bytes originais,
identidade/nome, engine version, timestamps e duração monotónica. Não persistir
RUNNING/FAILED fora desta transacção. Não inventar sucesso ou rollback global.

O digest vincula o estado **inicial** aprovado. Bootstrap/migrations confirmadas
evoluem esse estado como parte do plano, sob o mesmo lock. Nova invocação sempre
reobserva/recompila; sucesso parcial ou estado alterado exige outro digest/aprovação.
Rollback confirmado sem mudanças pode produzir o mesmo digest: um hash determinístico
não é token de uso único nem prova nova revisão humana. Requer invocação/consentimento
explícito do operador, nunca retry automático. OUTCOME_UNKNOWN exige reconciliação
prévia numa sessão nova; não pressupor que o rollback ocorreu.

## Falhas e libertação

Erro de statement: rollback da migration, terminar sequência e preservar anteriores
confirmadas. Falha/loss de ligação/ack em commit, rollback falhado ou resultado
não inequívoco: OUTCOME_UNKNOWN, abort/close e terminar, sem setAutoCommit(true),
sem retries, sem avançar. Reconciliar numa nova sessão sob lock sem inventar repair.

Libertar advisory lock na mesma sessão em finally; falha exige descartar a sessão,
nunca devolver uma sessão possivelmente locked ao pool. O DataSource tem de
suportar descarte físico/abort e timeouts finitos; caso contrário, usar connection
dedicada não pooled. Não alterar configuração global/pools para contornar isso.
Falha de cleanup depois de commit confirmado não apaga essa evidência: informar
o prefixo confirmado e a falha de cleanup, sem repetir DDL já confirmado.

## Validação e gates para começar código

F6-002B integrado só depois dos dois testes locais e build completa aprovados.
ADRs 0015/0016 e este contrato precisam de aprovação específica. CI verde não
substitui nenhuma destas aprovações. Depois, implementar em pequenas fatias:
bootstrap canónico; lock/lifecycle; executor de uma migration; sequência/falhas;
CLI deploy apenas após os componentes seguros e seus testes.

PostgreSQL real: scripts revistos em schemas aleatórios, DDL+histórico rollback,
duas sessões concorrentes, timeouts/cancelamento, perda de ligação/ack de commit,
rollback/unlock falhado, reutilização de token, role sem DDL e metadados proibidos.
Testes sincronizados por barreiras/fault injection, não sleeps como prova de ordem.
Não operar produção nem modificar dados persistidos do playground.

## Consequências e alternativas

O subconjunto é conservador: locks e rejeições podem limitar ergonomia e
availability; a aprovação deve aceitar explicitamente esse custo. Locks menores,
tipos/expressões avançados e SQL não transaccional necessitam de desenho próprio.
Advisory lock isolado não impede DDL externo; parser isolado não verifica catálogo;
hash isolado não é autorização. Não usar nenhuma dessas simplificações como garantia.

Fontes: [locks PG17](https://www.postgresql.org/docs/17/explicit-locking.html),
[ALTER TABLE](https://www.postgresql.org/docs/17/sql-altertable.html).
