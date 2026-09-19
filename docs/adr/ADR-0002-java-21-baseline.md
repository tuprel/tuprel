# ADR-0002-java-21-baseline: Java 21 como baseline

Estado: Aceite

## Contexto e decisão

O runtime mínimo será Java 21. A build pode testar JDKs posteriores suportados, mas o bytecode/contrato base deve permanecer compatível com Java 21 até decisão explícita de alteração.

## Consequências

Esta decisão deve ser reflectida na implementação, testes e documentação. Alterações significativas exigem novo ADR que substitua explicitamente este quando aplicável.
