# Engineering Security Baseline

Data de revisão: 2026-09-19.

## SQL

OWASP recomenda prepared statements/parameterized queries como defesa primária contra SQL injection. O Jorvia transforma isto num invariant arquitectural: SQL estrutural e valores via bind permanecem separados.

Referências:

- https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html
- https://cheatsheetseries.owasp.org/cheatsheets/Query_Parameterization_Cheat_Sheet.html

## Gradle supply chain

A documentação Gradle recomenda dependency locking para builds reproduzíveis e dependency verification para verificar integridade/proveniência de artefactos. A baseline do Jorvia exige lockfiles versionados e verification metadata revisto antes da release.

Referências:

- https://docs.gradle.org/current/userguide/dependency_locking.html
- https://docs.gradle.org/current/userguide/dependency_verification.html

## GitHub

Dependency review deve impedir a introdução silenciosa de dependências vulneráveis quando a funcionalidade estiver disponível para o repositório. Code scanning/CodeQL deve ser configurado para Java/Kotlin e outras linguagens relevantes.

Referências:

- https://docs.github.com/en/code-security/concepts/supply-chain-security/dependency-review
- https://docs.github.com/en/code-security/code-scanning
