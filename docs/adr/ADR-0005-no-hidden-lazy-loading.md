# ADR-0005-no-hidden-lazy-loading: Sem lazy loading invisível por omissão

Estado: Aceite

## Contexto e decisão

Aceder a uma propriedade Java não deve, por si só, provocar I/O de base de dados. Relações são carregadas explicitamente por select/include ou APIs equivalentes aprovadas.

## Consequências

Esta decisão deve ser reflectida na implementação, testes e documentação. Alterações significativas exigem novo ADR que substitua explicitamente este quando aplicável.
