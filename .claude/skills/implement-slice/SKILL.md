---
name: implement-slice
description: Implementa uma pequena fatia previamente planeada do Jorvia, com testes e validação completa.
disable-model-invocation: true
---

Implementa apenas a fatia indicada em `$ARGUMENTS`.

Antes de editar, confirma que a fatia pertence à fase actual e que o contrato está definido. Se faltar uma decisão arquitectural, pára e propõe ADR.

Durante a implementação:

1. faz a alteração mínima necessária;
2. adiciona ou actualiza testes;
3. mantém APIs públicas documentadas;
4. preserva os guardrails de segurança;
5. não introduz dependências sem justificação;
6. executa testes focados;
7. executa `./gradlew check` antes de terminar, quando a build existir;
8. reporta ficheiros alterados, testes, riscos e trabalho restante.

Não avances para a fatia seguinte por iniciativa própria.
