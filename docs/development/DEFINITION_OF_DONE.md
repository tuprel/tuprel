# Definition of Done

Uma tarefa de implementação só pode ser marcada concluída quando os itens aplicáveis abaixo forem verdadeiros.

## Contrato

- comportamento esperado está descrito;
- API pública tem nomes e tipos deliberados;
- casos de erro estão definidos;
- non-goals continuam fora do scope.

## Implementação

- mudança respeita module boundaries;
- sem duplicação/abstracção desnecessária;
- sem secrets ou debug leftovers;
- nenhum warning novo ignorado sem razão.

## Segurança

- threat surface da mudança foi considerada;
- SQL values permanecem parametrizados;
- inputs e paths externos são validados adequadamente;
- logs/erros não expõem secrets.

## Testes

- testes focados passam;
- regression test existe para bug fix;
- PostgreSQL integration test existe quando comportamento depende de PostgreSQL;
- `./gradlew check` passa quando a build estiver estabelecida.

## Documentação

- JavaDoc/docs actualizados quando o contrato muda;
- plano/ADR actualizado quando a decisão mudou;
- exemplos públicos usam tipos explícitos e correspondem à API real.

## Entrega

- não existem mudanças não relacionadas escondidas;
- riscos/dívida deliberada estão documentados;
- critérios de saída da fase continuam satisfeitos.
