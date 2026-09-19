# Claude Code Setup adoptado neste projecto

Data de revisão desta configuração: 2026-09-19.

## Estrutura

O projecto usa:

- `CLAUDE.md` para instruções centrais persistentes;
- `.claude/rules/*.md` para separar regras por tópico;
- `.claude/settings.json` para guardrails partilhados;
- `.claude/settings.local.json` apenas para preferências locais não versionadas;
- `.claude/skills/<name>/SKILL.md` para workflows reutilizáveis;
- `.claude/agents/*.md` para revisores especializados.

## Segurança de Claude Code

O settings partilhado:

- bloqueia leitura de `.env`, credentials/secrets e chaves em locais comuns;
- bloqueia bypass permissions mode;
- limita reads fora dos working directories;
- pede autorização para push, commit, publish, Docker e git clean;
- nega force push, hard reset e `rm -rf` no formato comum.

Estas regras melhoram segurança mas regras de comando por padrão não substituem isolamento do sistema operativo. Em macOS/Linux/WSL2, recomenda-se sandbox estrito local com `allowUnsandboxedCommands: false`.

## Referências oficiais

- Claude Code settings: https://code.claude.com/docs/en/settings
- Permissions: https://code.claude.com/docs/en/permissions
- Memory/CLAUDE.md/rules: https://code.claude.com/docs/en/memory
- Skills: https://code.claude.com/docs/en/skills
- Subagents: https://code.claude.com/docs/en/sub-agents
- Hooks: https://code.claude.com/docs/en/hooks
- Sandboxing: https://code.claude.com/docs/en/sandboxing
