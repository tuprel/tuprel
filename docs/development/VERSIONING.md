# Versioning e Compatibilidade

## Antes de 1.0

APIs podem evoluir, mas breaking changes devem ser documentadas e justificadas. O formato de migrations merece cuidado adicional mesmo em previews porque fica persistido em projectos utilizadores.

## A partir de 1.0

Adoptar SemVer para artefactos públicos, com política explícita para:

- source/binary compatibility Java;
- generated code contract;
- schema language;
- migration file format/history table;
- CLI commands e exit codes;
- config files.

Breaking change intencional exige plano de migração e release notes.
