# Regras da API Java

- Baseline: Java 21.
- Exemplos públicos usam tipos explícitos em vez de `var`.
- Prefere tipos imutáveis, records quando adequados e builders apenas quando melhoram a API.
- Não exponhas tipos de JDBC directamente numa API de domínio sem necessidade.
- Nunca devolvas `null` para significar "não encontrado" numa API nova; define contrato explícito, normalmente `Optional<T>` quando apropriado.
- Não uses `Object` como escape para problemas de modelação.
- Evita overloads ambíguos e APIs que dependem de inferência frágil.
- Excepções públicas devem ter mensagens úteis, causa preservada e dados sensíveis redigidos.
- Uma API pública nova precisa de teste de contrato e JavaDoc.
- Não introduzas Lombok no core sem ADR.
