# ADR-0008-forward-migrations: Produção aplica migrations revistas

Estado: Proposta

## Contexto e decisão

`migrate deploy` deverá aplicar migrations versionadas existentes e nunca gerar automaticamente DDL novo em produção. Rollback automático universal não é prometido; recuperação deve respeitar a reversibilidade real da alteração e estratégia operacional documentada.

## Consequências

Esta decisão deve ser reflectida na implementação, testes e documentação. Alterações significativas exigem novo ADR que substitua explicitamente este quando aplicável.
