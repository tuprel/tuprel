---
name: release-check
description: Executa a checklist de readiness de uma release Tuprel sem publicar artefactos.
disable-model-invocation: true
---

Avalia a release indicada em `$ARGUMENTS` usando `plans/RELEASE_1_0.md` e a política de segurança.

Não publiques nada.

Verifica build, testes, compatibilidade, documentação, migrations, dependency locks, dependency verification, vulnerability scanning, artefactos, JavaDoc, changelog, licenças, SBOM/proveniência quando configurados e ausência de secrets.

Entrega uma lista de blockers e itens satisfeitos. Se um check não puder ser executado localmente, marca-o como não verificado em vez de presumir sucesso.
