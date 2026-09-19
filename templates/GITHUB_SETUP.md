# Activar GitHub Automation

Os ficheiros em `templates/github/` são templates, não workflows activos.

Na Fase 0, Claude deve:

1. confirmar a versão actual e recomendada das GitHub Actions oficiais;
2. confirmar a matriz Java/PostgreSQL aprovada;
3. copiar versões revistas para `.github/workflows/` e `.github/dependabot.yml`;
4. manter `permissions:` mínimas;
5. validar YAML e executar CI;
6. activar branch protection/rulesets manualmente no GitHub quando o repositório existir.

Isto evita que um template de 2026 seja tratado como eternamente actual.
