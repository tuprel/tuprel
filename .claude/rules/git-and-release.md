# Regras de Git e Release

- Não fazer push, force push, publicação ou release sem autorização explícita.
- Não usar `git reset --hard` para resolver problemas de implementação.
- Nunca commitar `.env`, chaves, tokens, credenciais ou dumps de produção.
- Não modificar migrations já publicadas; cria uma nova migration correctiva.
- Release deve passar todos os gates de `plans/RELEASE_1_0.md` e `/release-check`.
- Artefactos publicados devem ser reproduzíveis, assinados e acompanhados da proveniência definida na política de release quando essa infraestrutura existir.
