# Configuração Claude Code

A pasta `.claude/` contém configuração partilhada do projecto.

- `settings.json`: permissões e guardrails partilhados.
- `rules/`: regras permanentes por domínio.
- `skills/`: workflows reutilizáveis invocáveis no Claude Code.
- `agents/`: subagentes de revisão especializados.
- `settings.local.example.json`: exemplo de configuração local mais restritiva.

## Sandbox

Em macOS, Linux ou WSL2, copia `settings.local.example.json` para `settings.local.json` se quiseres activar sandbox estrito localmente. Não versionar `settings.local.json`.

Em Windows nativo, confirma primeiro a compatibilidade da versão de Claude Code. Para sandboxing com isolamento do sistema operativo, usa WSL2.

Depois de alterar settings, executa `/status` e `claude doctor` para confirmar que a configuração foi aceite.
