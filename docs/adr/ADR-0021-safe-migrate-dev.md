# ADR-0021 — Propostas e publicação segura com migrate dev

Estado **parcialmente aceite**, exclusivamente para o preview read-only da
F6-005C1. Aprovisionamento inicial, publicação e revisão do deploy permanecem
propostos. Depende do
[ADR-0020](ADR-0020-postgresql-catalog-observation.md). A revisão do protocolo
de escrita e a criação de artefactos exigem aceitação deste contrato.

## Contexto e decisão proposta

`migrate diff` compara todos os ficheiros locais com a intenção declarada. Para
preparar uma nova migration precisamos de uma referência com proveniência,
separada do estado desejado. A C1 de `migrate dev` propõe SQL. A publicação
explícita de um único artefacto v1 pertence à proposta C2. A revisão e a
aplicação pertencem ao `deploy` e usam a evidência revalidada do alvo vivo.

### Referência, proposta e interface

1. Descobrir `tuprel/migrations` e validar `schema.tuprel` com os componentes
   existentes. Confirmar o alvo e obter o par catálogo/histórico do ADR-0020.
   Exigir `NO_DRIFT` entre observação e replay da história aplicada. Na primeira
   versão, exigir catálogo local sem pendentes, inclusive drafts. Um
   schema vazio sem histórico fica pendente da revisão de aprovisionamento
   inicial descrita abaixo.
2. Projectar a intenção por `PostgresqlSchemaProjector` e comparar a referência
   com a projecção usando `SchemaDiff`. Exigir `REVIEW_REQUIRED` com preview
   completo do subconjunto aditivo aceite. Reusar o parser e o compilador de
   planos para validar a proposta inteira, os riscos, o alvo e a capacidade.
   Reaplicar a proposta ao estado lógico de referência e confirmar igualdade
   com o estado desejado antes de a mostrar ou publicar. É admissível um único
   ficheiro por invocação. Exceder o orçamento de um ficheiro recusa a proposta.
3. A C1 apresenta o preview, as fontes e os riscos. Na proposta C2, `--write`
   recalcula o plano e publica os bytes concretos que apresenta nessa
   invocação. Esta flag autoriza a criação local. A aprovação para aplicar
   só pode ser dada no fluxo de `deploy`, após rever os bytes publicados e o
   novo plano contextual. Uma alteração humana a um candidato ainda pendente
   exige nova validação e revisão. Ficheiros aplicados ficam imutáveis.

Contrato CLI aceite para C1.

```text
tuprel migrate dev --name <nome> --target-schema <schema> --confirm-managed-history [--schema <path>]
```

Manter defaults `tuprel/schema.tuprel` e `tuprel/migrations`. Reutilizar a
configuração explícita dos comandos DB, incluindo TLS e redacção. A confirmação
é por invocação e atesta proveniência do histórico e exclusão administrativa
de DDL externo, conforme ADR-0020. O estado continua sujeito à verificação
do adaptador. A ajuda é offline. Código 0 para preview ou ausência de alterações.
Código 1 para schema inválido. Código 2 para drift, referência desconhecida,
pendentes, SQL fora da gramática, limites, colisões ou falha de operação.
Sem alterações resulta em `NO_CHANGES` sem alocação de ID. Remoções, mudanças
de tipo, NOT NULL novo em tabela existente, renames e diferenças desconhecidas
bloqueiam a proposta inteira. Reset, reparação e DDL destrutivo ficam fora
desta versão.

API de C1. Um `MigrationDevPlan` no adapter contém resultado tipado
`NO_CHANGES`, `REVIEW_REQUIRED` ou `BLOCKED`, diagnósticos seguros e, apenas no
estado revisto, a proposta canónica e os riscos existentes. A designação
`REVIEW_REQUIRED` significa que a revisão é necessária. Um planeador recebe
`ValidatedSchema` e usa o observador, replay e compilador existentes. Uma
operação separada publica o candidato v1, devolvendo `MigrationFile` depois
de verificação da publicação. A operação recebe o contexto da proposta e
reconstrói os inputs necessários. Um snapshot ou SQL enviado pelo chamador
fica fora da cadeia de autoridade. A CLI trata argumentos, I/O e apresentação.

### Publicação v1 e atomicidade

Manter a convenção UTC, a validação do nome, os bytes UTF-8 estritos sem
BOM/NUL/CR e com LF final, os limites v1 e o SHA-256 dos bytes exactos.
Reutilizar `MigrationCreator` e `MigrationDirectory` para identidade, checksums,
capacidade e confinamento. Partilhar o mesmo lock JVM e o lock de ficheiro
`tuprel/.migrations.create.lock` com `migrate create`. O lock existente é
regular, vazio e persistente entre invocações. Usar aquisição limitada e
falha `BUSY`, sem remover o lock ou criar um namespace concorrente.

Para `--write`, tomar primeiro o lock de sessão do runner durante a
reobservação do alvo, usando o mesmo namespace e validação de sessão adaptada
ao modo read-only. A transacção de observação continua read-only. Tomar depois
o lock de ficheiros sem espera indefinida. Revalidar marker, root canónica,
identidades dos componentes, bytes do schema e lista ordenada de ficheiros e
checksums. Uma invocação só pode publicar a proposta calculada sobre esses
inputs. Recusar se algum mudou, se o novo ID colidir ou se não for estritamente
posterior aos existentes. Relógio atrasado e relógios iguais dão erro seguro.

Preparar uma pasta privada com identidade nova fora do catálogo, irmã da
root e no mesmo `FileStore`. Criar apenas `migration.sql` com `CREATE_NEW` e
`NOFOLLOW_LINKS`. Escrever todos os bytes, fazer `force` do ficheiro, fechar e
reler para verificar comprimento, hash, nome, gramática e conteúdo aprovado
para publicação. Validar novamente root, staging, lock e ausência do destino
sob o lock. Publicar o par pasta/ficheiro com uma mudança atómica de nome
no mesmo filesystem. A descoberta v1 vê a pasta completa quando fica visível.
O staging fica fora da descoberta e nunca altera o marker ou pastas existentes.

**Condição proposta para a garantia de não sobrescrita.** Em Java 21,
`ATOMIC_MOVE` pode substituir um destino existente conforme o provider. O
protocolo depende de um workspace local privado, com controlo operacional de
escritores e todos os criadores Tuprel no lock partilhado. Isto inclui excluir
processos com as mesmas permissões, sincronizadores e editores que possam
alterar o namespace durante a publicação. Um teste de ausência do destino
isolado não fecha a race. O lock coordena os participantes, sem impor o
contrato a processos externos. Suportar apenas providers locais verificados
nos testes, com identidade de paths confirmável. Se as condições de controlo
ou atomicidade estiverem indisponíveis, recusar `--write`. Caso se exija
garantia contra escritores externos, será necessária uma primitiva de rename
atómico *no-replace* com novo contrato de portabilidade. Não usar fallback para
copy, rename não atómico ou replace. [Contrato de Files.move no JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).

Recusar traversal, symlinks, junctions/reparse points não verificáveis, aliases
de case, destinos pré-existentes e mudanças de identidade antes ou depois do
move. Confirmar pelo `MigrationDirectory` que o catálogo contém a adição
esperada com os mesmos bytes e todas as entradas anteriores intactas. Uma
falha antes do move só pode limpar staging que a invocação ainda prove possuir.
Uma falha ambígua no move ou na verificação final devolve `PUBLICATION_UNKNOWN`
com instrução para inspeccionar o catálogo. Sem retry, delete ou reparação do
destino publicado. A atomicidade é de visibilidade no filesystem suportado.
A confirmação após perda de energia exige redescoberta e validação. Não se
promete fsync portável do directório ou durabilidade distribuída.

### Revalidação e aprovação de deploy

**Janela controlada.** O advisory lock do ADR-0017 coordena runners Tuprel,
sem impedir DDL SQL externo, troca de namespace ou failover. A revalidação
exige um alvo de migrations administrado: acesso DDL e escrita no histórico
reservados ao actor de migrations, sessões que partilhem essa capacidade
coordenadas pelo runner e administradores excluídos durante a janela. O
operador tem de estabelecer estes controlos antes de usar o fluxo. Para o
primeiro bootstrap, a revisão consulta o registo de aprovisionamento desse
database e schema, incluindo a identidade observada, e confirma que nunca
teve histórico. O cliente verifica a identidade e a forma observáveis. O
controlo de administradores, a proveniência e a exclusão de alterações de
topologia são precondições operacionais que o cliente não consegue deduzir
dos catálogos. Uma flag genérica ou o lock não servem como prova. Alvo sem
identidade estável ou sem estes controlos fica fora da execução com
verificação física.

Propor um protocolo de aprovação física separado do digest v1. O digest
interior continua a vincular alvo, história, ordem, bytes exactos dos
artefactos, preview, gramática e política conforme ADR-0016. O digest exterior
vincula esse valor, versão do perfil, identidade observada e digests canónicos
dos estados esperado/observado completos. A confirmação é explícita e
posterior à revisão humana de ficheiros, SQL e riscos. `--write`, resultados
de drift e hashes impressos para diagnóstico não são aprovações.

Usar o framing `S`/`I32`/`I64`/`B` do ADR-0016 e SHA-256. A proposta para o
digest exterior é `S("TUPREL-PG-PHYSICAL-DEPLOY")`, `I32(1)`, fingerprint
configurado, OIDs observados de database e namespace em `I64`, perfil de
cobertura versionado, digest interior, digest do estado esperado, digest do
estado observado e identidade do inventário. Cada fingerprint/digest é
`S(hex lowercase)` e o perfil é `S(nome)`. A identidade do inventário usa
contagem `I32` seguida de pares `S(nome)`, `I64(oid)` ordenados por nome exacto
para tabelas e índices da observação, incluindo a PK implícita e a relação de
histórico quando presente. Deste modo uma substituição detectável de objecto
com a mesma forma invalida a aprovação. O digest estrutural tem namespace próprio
`S("TUPREL-PG-CATALOG-SNAPSHOT")`, `I32(1)`, `S(perfil)`, `S(schema)` e listas
prefixadas por contagem. Ordenar tabelas, colunas e índices por nome exacto.
Cada tabela inclui `S(nome)`, colunas com `S(nome)`, `S(tipo base)`,
`B(nullable)`, `B(pk)`, e índices com `S(nome)` e chaves `S(coluna)` na ordem
definida. Os OIDs de relações verificadas são evidência de continuidade para
os gates na mesma sessão. OIDs de relação, tempos, texto de diagnóstico,
passwords e estatísticas não fazem parte da igualdade estrutural. Esta
proposta deve ter vectores golden antes de expor qualquer aprovação nova.

Antes de bootstrap do histórico ou DDL, o runner deve adquirir o lock de
sessão existente, validar identidade, redescobrir os ficheiros, ler história
e catálogo em snapshot conjunto, exigir `NO_DRIFT`, recompilar e comparar
o digest aprovado. Recusar mudanças de alvo, bytes, histórico, perfil ou
estado esperado/observado. Rejeitar `UNKNOWN` e `DRIFT_DETECTED`. Nenhuma
evidência de outra sessão ou guardada num preview anterior substitui esta
leitura. Congelar bytes aprovados em memória conforme o runner actual.

Manter o lock durante toda a operação. Em cada transacção de escrita,
aplicar os locks de relação e gates do ADR-0017 antes de reler os metadados
actuais. Comparar com o replay do prefixo esperado, incluindo apenas commits
confirmados desta invocação. Conservar a política `READ COMMITTED` do runner
com a janela de exclusão externa e verificações após os locks. Confirmar OIDs,
ausência de event triggers activos e parâmetros efectivos de DDL compatíveis
com o perfil, incluindo heap e tablespace implícitos. Verificar o estado
resultante esperado antes de confirmar DDL e histórico na mesma transacção.
Qualquer discrepância aborta a transacção corrente. Commits anteriores
confirmados mantêm o seu estado. Perda de confirmação de commit/rollback
mantém `OUTCOME_UNKNOWN` e exige reconciliação read-only em nova sessão.

### Compatibilidade e ordem de implementação

O formato v1 e os digests interiores mantêm a semântica existente. Para
evitar que ficheiros v1 gerados por `dev` atravessem um caminho sem verificação
física, propõe-se activar o novo gate obrigatório de `deploy` na versão que
disponibilizar `dev --write`. Uma instalação dessa versão deve recusar o digest
antigo como aprovação física, com instrução para voltar a gerar/rever o
preview. Os ficheiros antigos permanecem legíveis. Schemas fora do perfil
ficam bloqueados para esse deploy. É uma mudança deliberada de compatibilidade
do executor, sujeita à aprovação deste ADR e a testes antes de publicação.
Não inferir proveniência a partir de comentários, nomes ou flags. Em ambientes
que aceitem só deploy com verificação física, controlar também versões dos
runners usados, pois binários antigos não conhecem o gate novo.

Ordem proposta depois das aprovações. Primeiro F6-005A e B em PRs read-only.
Depois a proposta lógica e preview `dev`, sem `--write`. A revisão e validação
do protocolo de deploy precedem um PR separado de publicação atómica. Até
esses gates, o formato, comandos e executor existentes conservam o contrato
actual. Catálogo, DDL físico, drift e runner ficam no adapter PostgreSQL.
O módulo puro continua a gerir identidade, bytes e publicação de artefactos,
sem JDBC, schema ou dialectos. Sem nova dependência do runtime CRUD, novo
módulo ou segundo modelo de schema.

## Alternativas e consequências

- Gerar e aplicar numa única invocação simplificaria a experiência e eliminaria
  a revisão dos bytes publicados. A separação proposta permite revisão e
  reproduz o protocolo de autorização existente.
- Publicar com `CREATE_NEW` directamente no destino protegeria contra replace,
  mas exporia uma pasta incompleta. A publicação atómica em workspace
  controlado evita esse estado e limita a portabilidade. Um provider sem
  suporte comprovado conserva o modo preview.
- Manter um deploy legado alternativo na versão de `--write` permitiria aplicar
  os mesmos bytes v1 por outro caminho. O gate obrigatório fecha essa via e
  custa uma nova revisão do digest e menor cobertura de schemas.
- Usar apenas o schema desejado ou apenas `migrate diff` eliminaria a consulta
  de DB, mas não provaria o estado aplicado. A recusa de pendentes reduz a
  ambiguidade da primeira versão e serializa a geração de novos candidatos.

## Critérios de aceitação

- Golden de CLI, SQL, bytes LF e checksum. Round-trip com o parser e igualdade
  do estado esperado após replay. Recusa integral de drift, desconhecidos,
  pendentes, tipos/defaults/FK fora do perfil, removals, renames e limites.
- Windows e Linux com processos concorrentes `create`/`dev`, mesmo segundo,
  clock skew, destino existente, symlinks, aliases, junctions, provider sem
  move atómico e troca de identidade. Injeção de falhas antes/depois do move,
  write/force/fecho e redescoberta. Comparar bytes dos artefactos anteriores e
  provar ausência de exposição de uma migration parcial ao leitor v1.
- PostgreSQL 17 real. Alterar alvo, ficheiro, checksum, história, catálogo e
  namespace entre preview e aplicação. Simular DDL concorrente, perda de
  ligação e confirmação de commit incerta. Verificar recusa antes de escrita,
  revalidação do prefixo, rollback quando o resultado diverge, ausência de
  auto-repair e reuso recusado de aprovação antiga quando o contexto mudou.
- Reutilizar gates locais, CI, CodeQL e Dependency Review. Preservar Java 21
  e dados do playground. Registar evidência no plano e confirmar a main após
  cada integração. CI verde valida a entrega, ficando a aceitação do contrato
  dependente de decisão explícita.

## Revisão pendente de publicação e compatibilidade

A [proposta para decisão de C2/C3](ADR-0021-c2-c3-review.md) especifica
o aprovisionamento inicial, a publicação por plataforma e o corte de
executores. As decisões abaixo permanecem propostas. Em caso de aprovação,
o contrato seleccionado nessa revisão substitui as alternativas desta secção.

**Publicação sem sobrescrita.** O lock de migrations coordena processos
Tuprel cooperantes. Outros processos com acesso de escrita podem criar o
destino depois da verificação de ausência. Em Java 21, a semântica de
`ATOMIC_MOVE` com destino existente depende do provider. A revisão deve
escolher entre limitar a garantia a um workspace de escrita controlada
com recusa fora desse perfil, exigir uma primitiva atómica com no-replace
comprovado por plataforma, ou conservar apenas preview. Uma primitiva
específica exige decisão sobre distribuição, dependências e suporte.

**Portabilidade e falhas.** Validar NTFS e os filesystems Linux declarados
suportados. Incluir aliases case-insensitive, junctions, symlinks, staging
e destino em volumes distintos, concorrência e destino criado entre a
última validação e o move. Comparar a árvore e os bytes anteriores em cada
falha. Distinguir falha antes do move, publicação verificada e resultado
incerto. A recuperação de um resultado incerto exige redescoberta
read-only do ID e checksum e confirmação manual. Atomicidade para leitores
e durabilidade após perda de energia são garantias distintas. Definir
a garantia de durabilidade por provider antes de a documentar.

**Compatibilidade dos executores.** Testar um executor anterior contra o
mesmo artefacto v1. Um binário antigo com credenciais de escrita pode
aplicar o artefacto sem o gate físico do binário novo. A revisão deve
definir a restrição das credenciais e canais de deploy a executores
compatíveis, a sequência de rollout e rollback e a renovação das
aprovações dos planos. Se essa restrição não puder ser estabelecida,
avaliar uma alteração explícita do formato que executores antigos
recusem, com análise de compatibilidade separada, ou manter a escrita
desactivada. O contrato actual de deploy continua em vigor.

**Aprovisionamento inicial.** A aprovação do ADR-0020 para a F6-005B exige
histórico validado para `NO_DRIFT`, mesmo num schema vazio. A referência
proposta para um alvo comprovadamente novo necessita de evidência própria
de aprovisionamento e revisão neste ADR antes de autorizar escrita.
