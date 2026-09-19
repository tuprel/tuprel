# Requisitos de Segurança

## SR-001 SQL parameterization

Todos os valores variáveis devem ser enviados separadamente do SQL. Testes devem provar que payloads com quotes, comments e operadores não alteram a estrutura da query.

## SR-002 Identifier safety

Table/column/schema/index names devem ser validados e quoted pelo dialect. Metadata externa nunca é injectada directamente em Java source ou paths.

## SR-003 Secret redaction

Passwords, tokens e JDBC credentials nunca aparecem em logs normais. Erros de conexão redigem userinfo/password. Bind values sensíveis não são logados por omissão.

## SR-004 Unsafe API separation

Qualquer API que aceite SQL estrutural fornecido pelo developer deve distinguir parâmetros seguros de fragmentos estruturais. Uma API que permita bypass de segurança deve incluir `unsafe` no nome ou mecanismo igualmente explícito aprovado em ADR.

## SR-005 Migration safety

Mudanças com potencial de data loss são detectadas quando tecnicamente possível. `migrate dev` pode exigir confirmação/flag. `migrate deploy` só aplica artefactos versionados existentes.

## SR-006 Migration integrity

Migration history inclui identificador, ordem, checksum, timestamps e estado. Alteração de uma migration aplicada deve ser detectada.

## SR-007 Migration concurrency

Só um migrator pode alterar o schema no mesmo target de cada vez. O lock deve ser libertado de forma segura em sucesso/falha.

## SR-008 Filesystem confinement

Codegen, format e migration generation não escrevem fora das roots autorizadas devido a nomes/configuração maliciosa.

## SR-009 Dependency security

A build deve activar dependency locking e dependency verification antes da primeira release pública. O metadata gerado precisa de revisão humana durante bootstrap.

## SR-010 CI security

Dependabot, dependency review e code scanning devem fazer parte do repositório público quando disponíveis no plano GitHub usado.

## SR-011 No production data in tests

Fixtures são sintéticas. Dumps reais não entram no repo.

## SR-012 Fail closed on ambiguous destructive operations

Se o migration engine não consegue determinar com confiança que uma alteração é segura, classifica como requer revisão em vez de presumir segurança.
