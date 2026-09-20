# Checklist para entregar ao Claude Code

## Antes da primeira sessão

- criar uma pasta/repositório Git vazio ou usar este pacote como raiz;
- confirmar que Java 21+ está instalado;
- instalar/actualizar Claude Code;
- executar `claude --version` e `claude doctor`;
- iniciar Claude Code na raiz do repositório;
- rever `.claude/settings.json` antes de confiar no workspace;
- em macOS/Linux/WSL2, considerar sandbox estrito copiando `.claude/settings.local.example.json` para `.claude/settings.local.json`;
- não adicionar credenciais reais ao repositório.

## Primeira sessão

- activar Plan Mode;
- colar `CLAUDE_FIRST_PROMPT.md`;
- confirmar a fase actual no master plan e aprovar/corrigir o primeiro slice;
- só depois permitir implementação.

## Durante o projecto

- usar `/plan-feature <feature>` antes de features grandes;
- usar `/implement-slice <slice>` para execução incremental;
- usar `/architecture-review`, `/security-review`, `/sql-review` e `/migration-review` nas respectivas áreas;
- não pedir "faz o ORM todo" numa única execução;
- exigir relatório de testes e riscos no fim de cada fase.
