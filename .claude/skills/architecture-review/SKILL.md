---
name: architecture-review
description: Revê uma alteração do Jorvia procurando violações de boundaries, acoplamento, ciclos e contratos instáveis.
allowed-tools: Read Grep Glob
---

Revê `$ARGUMENTS` como arquitecto de biblioteca Java.

Verifica:

- direcção das dependências;
- separação entre parser, validator, codegen, runtime, dialect e integrations;
- leakage de tipos internos para API pública;
- acoplamento a framework;
- estado global/mutabilidade/concurrency;
- extensibilidade sem abstrações especulativas;
- compatibilidade pública;
- necessidade de ADR.

Entrega achados por gravidade, com ficheiro/local e recomendação concreta. Não atribuas uma nota numérica.
