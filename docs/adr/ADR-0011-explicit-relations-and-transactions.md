# ADR-0011: Relações nos valores gerados e transacções explícitas

Estado: Aceite para o subconjunto da Fase 5

## Contexto

O RFC-002 manteve os valores gerados sem relações, e o ADR-0009 fixou uma
connection em autocommit por operação. A Fase 5 precisa de devolver relações
carregadas, executar várias operações numa transacção e controlar concorrência,
sem lazy loading, sessões nem transacções escondidas.

## Decisão

- **Relações nos records.** Cada field de relação torna-se um componente
  `TuprelRelation<V>` do record gerado, depois dos escalares. O estado
  carregado ou não carregado é explícito, e ler uma relação não carregada falha
  sem I/O. O record mantém um construtor só com escalares. Alternativa
  rejeitada: tipos de resultado separados por combinação de includes, que
  multiplicariam classes geradas e afastariam a API da especificação.
- **Carregamento em lote no runtime.** O carregamento vive no
  `RelationLoader`, que é testado directamente. O código gerado declara apenas
  a relação (colunas, tabela alvo e forma de anexar o valor). O custo é fixo
  por relação e nível, nunca por linha.
- **Metadata por model.** `P.metadata.XMetadata.TABLE` passa a conter o
  `ModelTable`, partilhado pelo cliente e pelos includes. As tabelas alvo são
  referidas de forma diferida, para evitar ciclos de inicialização entre
  classes geradas.
- **Transacção como instância ligada.** `TuprelDatabase.transaction` cria uma
  instância ligada a uma connection. Não usa estado global, `ThreadLocal`
  partilhado nem sessão. O cliente gerado passa essa instância a um
  `TuprelClient` novo. A integração com gestores de transacções de frameworks
  (Spring) fica para a Fase 7 e terá de usar o mesmo contrato.
- **Rigor do schema.** Relações inversas têm de emparelhar com uma relação dona
  (RFC-003), e `@version` passa a fazer parte da linguagem (RFC-001).

## Consequências

- Records com relações têm um construtor canónico mais largo, e a igualdade
  inclui o estado das relações.
- Schemas com relações inversas sem par, antes aceites, passam a ser inválidos.
- Models versionados deixam de ter `updateById` sem versão.
- A execução JDBC continua síncrona e sem retry automático.
- `@map`/`@@map`, a relação N:N implícita, escrita aninhada, upsert e retries
  ficam para fases seguintes.
