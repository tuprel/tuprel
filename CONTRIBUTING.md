# Contribuir para o Tuprel ORM

## Fluxo

1. abre ou referencia uma issue/decisão;
2. identifica a fase e o módulo afectados;
3. lê os ADRs relevantes;
4. cria uma alteração pequena;
5. adiciona testes apropriados;
6. executa `./gradlew check` e testes de integração relevantes (comandos e lifecycle em `docs/development/BUILD_AND_TEST.md`);
7. actualiza documentação quando o contrato público muda;
8. submete PR com riscos, compatibilidade e evidência de testes.

## Alterações de arquitectura

Mudanças em módulos, dependências, formato do schema, API pública, modelo de migrações, compatibilidade de base de dados ou segurança exigem ADR.

## Dependências

Uma nova dependência deve justificar:

- por que é necessária;
- por que código próprio não é preferível;
- licença;
- manutenção/actividade do projecto;
- impacto transitivo;
- risco de supply chain;
- impacto de runtime e desempenho.

## Segurança

Vulnerabilidades não devem ser publicadas primeiro em issues públicas. Consulta `SECURITY.md`.
