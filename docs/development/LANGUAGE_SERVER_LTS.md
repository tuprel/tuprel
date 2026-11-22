# Language Server — certificação D2

Base de referência: `1836ff7d3f1553e156526461691d5304240d72d8`.
Java 21 continua como mínimo público e toolchain interna. A activação do
suporte público Java 17 pertence à Fase E do ADR-0024.

## Alteração e referência diferencial

O servidor anterior falha em Java 17 com `UnsupportedClassVersionError`
(major 65, máximo 61). A adaptação limita-se ao dispatch de hover por
`instanceof` e ao target 17. Os quatro ramos e a recusa de null mantêm-se.
Os switches de enums já são compatíveis. Framing, limites, diagnósticos,
formatting, completion, definição e lifecycle não foram alterados.

`language-server-reference.jsonl` foi capturado do servidor anterior em
Java 21, antes da alteração, pelo consumidor externo `LanguageServerProbe`.
Contém as respostas completas de initialize, diagnóstico, limpeza após
edição, formatting, hover, definição positiva e negativa, completion, JSON
inválido e shutdown. Cada resposta valida o ID e o protocolo; o consumidor
verifica stdout enquadrado, stderr vazio e exit 0. O transcript usa LF para
permitir a mesma comparação SHA-256 nas duas plataformas. Não contém dados
de utilizador. Os testes anteriores cobrem também os restantes símbolos,
UTF-16, mensagens inválidas, budgets e falhas de análise.

## Execução reproduzível

```powershell
./test-support/distribution/Verify-LanguageServerLts.ps1 `
  -Jdk17 /path/to/jdk17 -Jdk21 /path/to/jdk21 -Jdk25 /path/to/jdk25
```

O script constrói `installDist` uma vez com JDK 21 e verificação estrita.
Copia a distribuição para uma pasta temporária fora do checkout, com espaços,
e reutiliza os mesmos JARs nas três JVMs. Executa o launcher instalado e a
invocação directa por classpath utilizada pelo editor. Regista as JVMs reais,
SHA-256, inventário de classes/major versions, ABI e transcripts em
`build/java-lts-d2/evidence`. `jdeps --multi-release 17 --recursive` verifica
o grafo entregue. As classes base e todas as variantes seleccionáveis até
17 são inspeccionadas; variantes posteriores não são confundidas com classes
carregáveis em Java 17. As dependências são schema, Gson 2.15.0 e
error_prone_annotations 2.50.0; o processador Error Prone não é distribuído.

`-BaselineDistribution` compara também o inventário e a API pública/protegida
com uma cópia anterior de installDist e repete a captura com esse servidor.
`-Java17Container`, apenas em Linux, executa a mesma distribuição numa imagem
Temurin 17 fixada por digest, sem rede, sem checkout ou JDK do host, como UID
não privilegiado. Isto prova o consumidor Java17-only, separadamente do build.

Os testes existentes podem executar integralmente nas três JVMs:

```powershell
./gradlew :tuprel-language-server:integrationTest -PlspTestJava=17 `
  '-Dorg.gradle.java.installations.paths=/path/to/jdk17,/path/to/jdk21,/path/to/jdk25' `
  --no-daemon --dependency-verification strict
```

O workflow `Language Server LTS` conserva os resultados por plataforma e JDK
como artefactos do run. Os restantes workflows não são substituídos.

## Estado das provas

Windows local: launcher e classpath passaram em Java 17, 21 e 25, com respostas
iguais à referência e sem alterações à distribuição durante a matriz.
Os 18 testes stdio existentes passaram em cada uma das três JVMs, sem skips.
`spotlessCheck check build` e o build independente do playground passaram
sequencialmente com verificação estrita. A comparação com a distribuição
anterior preservou o inventário de classes e a API pública/protegida.
Os resultados de Linux são registados no PR e nos jobs do HEAD correspondente;
não são inferidos deste ensaio local.

A certificação do Extension Host pertence ao PR separado de `tuprel-editors`,
fixado ao HEAD Java cuja matriz esteja aprovada. A distribuição não inclui
um runtime e esta entrega não publica extensões nem activa Java 17 no produto.
