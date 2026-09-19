# Fase 7 - CLI, Spring Boot, Gradle e Seed

## CLI

Consolidar `init`, `validate`, `format`, `generate`, migrations, `db seed`, diagnostics e exit codes.

## Spring Boot

- starter separado;
- reutilizar `DataSource` da aplicação;
- lifecycle adequado;
- integração transaccional só depois de contrato explícito;
- configuração com metadata e redaction.

## Gradle

- task de generate ligada ao compile de forma previsível;
- generated source root registada;
- cache/up-to-date inputs e outputs correctos;
- não executar migrations automaticamente durante compile.

## Seed

Seed é código/aplicação explícita, nunca comportamento automático de produção.
