# ADR-0006-prepared-statements: Separação obrigatória entre SQL e valores

Estado: Aceite

## Contexto e decisão

Todos os valores variáveis usam binding/PreparedStatement ou mecanismo semanticamente equivalente. O SQL renderer produz SQL e uma sequência tipada de binds. Identificadores passam por regras próprias de validação/quoting.

## Consequências

Esta decisão deve ser reflectida na implementação, testes e documentação. Alterações significativas exigem novo ADR que substitua explicitamente este quando aplicável.
