# ADR-0003-postgresql-first: PostgreSQL como primeiro dialecto

Estado: Aceite

## Contexto e decisão

A 1.0 aprofunda PostgreSQL em vez de declarar compatibilidade superficial multi-database. Abstracções de dialect devem permitir evolução, mas não serão desenhadas a partir de requisitos imaginários de cinco bases diferentes.

## Consequências

Esta decisão deve ser reflectida na implementação, testes e documentação. Alterações significativas exigem novo ADR que substitua explicitamente este quando aplicável.
