---
name: migration-review
description: Revê uma alteração do migration engine ou uma migration quanto a integridade, data loss, locking, drift e deploy seguro.
allowed-tools: Read Grep Glob
---

Revê `$ARGUMENTS`.

Verifica:

- upgrade a partir do estado anterior;
- checksums e history table;
- lock para execuções concorrentes;
- atomicidade e comportamento quando DDL não puder ser totalmente transaccional;
- data-loss detection;
- rename vs drop/create;
- defaults e NOT NULL em tabelas existentes;
- índices e impacto de locks;
- drift detection;
- idempotência apenas onde o contrato a exigir;
- recuperação de falhas;
- separação entre `migrate dev` e `migrate deploy`.

Não alteres uma migration já considerada publicada.
