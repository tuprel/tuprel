# F6-001 — Fundação CLI e ficheiros de migrations

Estado: implementada para revisão; base `6c771ef` (Fase 5 consolidada).
Contratos de DB continuam propostos, sem aprovação nem implementação.

## Contrato e âmbito

- CLI JDK-only: help global/por comando, versão de desenvolvimento verdadeira,
  init exclusivo, `--schema` e dry-run; sintaxe posicional e exit codes 0/1/2
  anteriores preservados. Nenhum comando de migrations anunciado como executável.
- Reutilizar SchemaCompiler, SchemaFormatter, JavaCodeGenerator e o controlo
  de ownership de GeneratedSourceWriter; diagnostics sem conteúdo do schema.
- `tuprel-migrate`: identificação `uuuuMMdd_HHmmss_nome`, descoberta read-only,
  snapshots imutáveis, SHA-256 exacto, formato UTF-8/LF versionado e limitado.
- Sem JDBC, histórico, execução, diff ou configuração de produção nesta entrega.
- Adapter PostgreSQL, histórico, aprovação de SQL e recovery ficam em ADRs
  propostos, bloqueando F6-002/F6-003 até aprovação explícita.

## Passos verificáveis

1. Fixar RFC-006, contrato CLI e ADRs; distinguir contrato desta fatia de decisões
   de execução pendentes. Documentar ameaças, limites e alternativas.
2. Implementar modelo/reader de migrations, sem dependências de produto ou externas;
   testar IDs, bytes, determinismo, colisões, limites, paths, links e snapshots.
3. Implementar CLI segura, sem alterar builds/configurações do consumidor;
   testar compatibilidade, dry-run, erros redigidos e distribuição real.
4. Integrar módulo nos quality gates; manter testes de symlink obrigatórios no
   Linux, sem simular sucesso no Windows sem privilégios.
5. Executar testes focados e build/check completos com PostgreSQL local, depois
   build do playground sequencialmente. Verificar scripts reais PowerShell/Bash
   onde disponíveis e reportar plataformas não executadas.
6. Rever diff, secrets, locks, JavaDoc e documentação; commits pequenos e PR
   com comandos reproduzíveis. Sem merge ou publicação.

## Ameaças e limites

Recusar traversal em nomes/roots do catálogo, symlinks e entradas inesperadas;
leitura limitada e sem seguir links no SQL; não confundir checksum com aprovação.
Init usa criação exclusiva e nunca substitui uma pasta Tuprel existente; falhas
parciais exigem inspecção, sem apagar automaticamente ficheiros do utilizador.
O workspace é local e controlado pelo developer: não se promete sandbox contra
outro processo com as mesmas permissões a trocar directorias durante I/O.

## Critérios de aceitação

Comandos existentes e playground funcionais; novos comportamentos testados;
nenhuma ligação a DB introduzida nesta fatia; dependências externas inalteradas;
CI/CodeQL sem gates enfraquecidos; contratos de DB claramente pendentes.
