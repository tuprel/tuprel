# Error Model - Requisitos

A hierarquia final será desenhada antes do runtime público, mas deve cobrir:

- `JorviaException` como base de falhas da biblioteca;
- schema parse/validation diagnostics com source spans;
- code generation failures;
- configuration errors;
- query construction errors;
- database execution errors com SQL seguro/redigido;
- constraint violations classificáveis quando PostgreSQL fornece dados suficientes;
- timeout/cancellation;
- transaction errors;
- migration planning/apply/history/drift errors;
- introspection errors.

Mensagens devem ser accionáveis e preservar `cause`, mas nunca incluir password ou connection string completa com credenciais.
