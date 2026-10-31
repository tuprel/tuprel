# ADR-0018 — Comparação lógica offline de schemas PostgreSQL

Estado: **proposto**, para revisão na F6-004A. Não altera os ADRs 0015–0017,
a gramática, os bytes dos digests ou a autorização de execução. A integração
deste novo contrato público aguarda aprovação explícita.

## Contexto

O parser DDL aceite já produz um AST fechado, mas o planner de deployment
compara ficheiros/histórico, não dois estados de schema. O schema.tuprel também
suporta defaults, relações, enums e tipos fora da gramática de migrations.
Projectá-lo parcialmente e omitir esses elementos produziria uma falsa igualdade.
F6-004 precisa de uma fundação verificável antes de expor diff na CLI.

## Decisão proposta

Introduzir SchemaSnapshot e SchemaDiff no adapter PostgreSQL existente. São
APIs sem I/O, dependências novas ou alterações ao grafo modular. Reutilizam
SqlIdentifier, DdlStatement e o renderer DdlParseResult; nenhum parser novo.
O domínio tuprel-migrate permanece independente do dialecto.

Um snapshot representa **apenas o estado lógico completamente conhecido**
obtido por replay ordenado do AST aceite a partir de um schema vazio. Não é
uma leitura do catálogo nem prova de que uma base existente tem esse estado.
fromParsed recusa qualquer resultado de parsing falhado, incluindo drafts
comentados; nunca transforma um prefixo válido ou SQL desconhecido em estado
vazio. replay recebe exclusivamente nós já reconhecidos, sem SQL raw.

- Um schema explícito por comparação; nomes ASCII/case-sensitive já validados.
- CREATE TABLE deve preceder ADD COLUMN/CREATE INDEX sobre essa tabela.
  Relações/colunas duplicadas, referências ausentes, colisões explícitas de
  nomes de tabela/index e prefixos reservados _tuprel_ são recusados.
- Tabelas/colunas/indexes são ordenados por nome exacto. Ordem de declaração
  de tabelas/colunas não faz parte da equivalência lógica; ordem das chaves
  de um index faz. Não promete preservar ordinais físicos ou SELECT *.
- Tipos/nullability/PK inline e indexes simples são os do AST aceite. Indexes
  implícitos da PK não são inventariados por nome; defaults, FKs, ownership,
  collation, opclasses e restantes propriedades físicas não são modelados.
  Um futuro leitor de catálogo deve sinalizar desconhecidos, nunca preencher
  este modelo descartando propriedades não compreendidas.
- Máximo 4096 statements e 16384 objectos (tabelas, colunas e indexes explícitos)
  por snapshot. Preview respeita os limites existentes de 4096 statements e
  16 MiB; input, comparação e resultados são imutáveis, sem estado global.

## Diferenças e preview

Comparar nomes exactos, sem inferência de rename. Table/column/index removidos
são reportados explicitamente. Colunas com tipo, nullability ou PK diferentes
produzem diferenças separadas; indexes com chaves em ordem diferente são
alterados. Mover/renomear um index corresponde a remoção e adição.

Só criar tabela, adicionar coluna nullable sem default/PK e criar index simples
produzem preview, sempre pelo renderer aceite. NOT NULL numa tabela nova pode
ser representado; acrescentá-lo a uma tabela existente fica bloqueado.
Remoções e modificações são reconhecidas mas **não renderizadas**. Uma alteração
dessas bloqueia todo o preview, incluindo adições independentes.

Estados: NO_CHANGES (igualdade lógica), REVIEW_REQUIRED (preview completamente
representável), BLOCKED (diferença não suportada ou limite excedido). Não há SAFE.
PHYSICAL_STATE_NOT_CHECKED está sempre presente. Remover tabela/coluna ou mudar
tipo sinaliza POTENTIAL_DATA_LOSS, inclusive widening sem uma política aprovada.
ADD COLUMN explicita ACCESS EXCLUSIVE; CREATE INDEX normal explicita bloqueio
de writes/custo. Modificações sem estratégia têm LOCK_IMPACT_NOT_MODELED; não se
estima um lock para SQL que não é gerado. Sintaxe desconhecida recusa o snapshot.

O preview não é migration, assinatura, digest nem aprovação. Não cria ficheiros
ou chama o runner. Para aplicar uma proposta será preciso produzir artefactos
revistos e cumprir integralmente o protocolo existente de deploy. Nenhuma
expansão de SQL ou escrita de migrate dev decorre desta proposta.

## Alternativas e trade-offs

- Projectar schema.tuprel já: integra directamente a experiência de produto,
  mas exige primeiro decidir mapping físico e diagnósticos para cada atributo.
  F6-004B deve reutilizar SchemaCompiler e as convenções reais do codegen/runtime,
  com contrato específico; não introduzir um parser paralelo.
- Comparar SQL textual: não distingue equivalência semântica nem propriedades
  conhecidas. Rejeitado; comentários/formatação não são diferenças lógicas.
- Ler o catálogo agora: mistura a fundação pura com consistência, permissões e
  completude da introspecção. F6-005A terá um contrato separado antes do adapter.
- Renderizar parcialmente alterações suportadas: poderia sugerir aplicação
  incompleta. Rejeitado; todo o preview falha se houver qualquer bloqueador.

## Sequência e aprovação

F6-004A entrega esta API, testes adversariais/concorrentes, prova de preview em
schemas PostgreSQL isolados e exemplo reproduzível. A revisão deste ADR é gate
de integração, separado da CI. F6-004B definirá a projecção completa ou recusada
de schema.tuprel; F6-004C só publicará migrate diff com fontes/opções/exit codes
definidos e testes dos launchers. Nenhum comando fictício entretanto.

F6-005A definirá catálogo suportado, snapshot read-only, permissões e cobertura
para distinguir drift confirmado de desconhecido. F6-005B/dev exigirá contrato
de geração de artefactos e aprovação contextual antes de novas operações de
escrita. Sem reset, reparação ou adopção do bootstrap do playground.

Testes/gates e estado factual em [PHASE_6_EXECUTION](../../plans/PHASE_6_EXECUTION.md).
