# Fase 2 - Java Code Generation

## Scope

Gerar Java determinístico a partir do schema validado.

## Entregáveis

- model/value types;
- fields metadata type-safe;
- create/update inputs;
- filter skeletons necessários à API futura;
- client/model entry points mínimos;
- naming collision handling;
- generated source marker/header;
- incremental-safe output/cleaning sem apagar ficheiros externos;
- `jorvia generate`.

## Segurança

Nomes do schema não podem escapar do output root nem injectar source arbitrário.

## Gate

Golden tests + compilação real do generated Java. Mesmo input/version deve produzir output equivalente.
