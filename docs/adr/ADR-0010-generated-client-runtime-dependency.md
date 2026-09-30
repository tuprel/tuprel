# ADR-0010: Cliente gerado sobre o runtime e mapeamento físico por identidade

Estado: Aceite para o subconjunto da Fase 4

## Contexto

O RFC-002 garantiu que todo o código gerado na Fase 2 compilava apenas com o
JDK e deixou `TuprelClient` e filtros executáveis para mais tarde. O ADR-0009
exigiu nomes físicos explícitos até existir um contrato de mapeamento gerado.
A Fase 4 precisa de um cliente gerado operacional e type-safe, sem strings
mágicas nem construção manual de SQL estrutural.

## Decisão

- **Dependência do código gerado.** Models, enums, inputs, `TuprelField`,
  `TuprelModel`, `TuprelBytes`, `TuprelJson` e `TuprelSchema` continuam
  JDK-only. Os ficheiros `P.where`, `P.order`, `P.client` e `TuprelClient`
  compilam contra `tuprel-runtime`, que expõe `tuprel-sql` como API.
  `tuprel-codegen-java` continua a não depender do runtime. Só o código que
  gera referencia os seus tipos por nome, e os testes compilam esse código com
  o runtime no classpath.
- **Superfície usada pelo código gerado.** O código gerado usa apenas os
  tipos públicos documentados de `dev.tuprel.runtime.query`, mais
  `TuprelDatabase` e `RenderedSql`. A lógica de execução vive em
  `ModelOperations`, que é testado directamente, e não em texto gerado.
- **Mapeamento físico por identidade.** Até existirem `@map` e `@@map`, a
  tabela chama-se exactamente como o model e cada coluna exactamente como o
  seu campo. Ambos são sempre citados e por isso sensíveis a maiúsculas: o
  `model User` lê `"User"`, e o campo `createdAt` lê `"createdAt"`. O gerador
  rejeita com `TUPREL-CODEGEN-014` nomes de model ou de coluna acima de 63
  caracteres, o limite de identificadores do PostgreSQL. Os nomes já são
  identificadores ASCII validados.
- **Visibilidade de tipos gerados.** Cada tipo de topo gerado tem
  `@javax.annotation.processing.Generated("dev.tuprel.codegen")`. A anotação
  é JDK-only, tem retenção de source e usa o nome qualificado.

## Consequências

Um projecto que compila o cliente gerado precisa de `tuprel-runtime` e de um
dialecto (hoje `tuprel-postgresql`) no classpath. Projectos que só usam os
valores gerados continuam a compilar apenas com o JDK. Isto altera o
contrato do RFC-002 para os packages `where` e `order`, cujos acessores
deixam de devolver `TuprelField`. Não existe release publicada, e a
alteração fica registada no RFC-004 e no changelog.

Com o mapeamento por identidade, a base de dados tem de usar os nomes do
schema até existirem `@map`/`@@map` e migrations. Um futuro `@map` altera
apenas os nomes passados a `ModelTable` e `Field`; a API gerada não muda.
