# ADR-0024 — Compatibilidade com versões Java LTS

Estado **Proposto**. Se aceite, substitui a decisão de runtime mínimo do
[ADR-0002](ADR-0002-java-21-baseline.md). A mudança de suporte público
depende da certificação descrita abaixo. A baseline em vigor é Java 21.

## Contexto e decisão proposta

Uma aplicação Java 17 deve conseguir usar a CLI, gerar código e executar o
cliente sem instalar outro JDK por exigência do Tuprel. Propor Java 17 como
mínimo para os artefactos distribuídos e certificar os mesmos bytes em Java
17, 21 e 25. Manter JDK 21 como toolchain interna inicial. Usar
`options.release.set(17)` nas compilações de produção elegíveis, incluindo
as do plugin e do starter, e publicar os atributos Gradle JVM coerentes.
Fixar também Java 17 no contrato do código gerado. Os testes que certificam
o consumidor devem compilar e executar no JDK de cada célula.

As provas abrangem CLI com migrations, bibliotecas, código gerado, integração
Maven, plugin Gradle, starter Spring e instalador por perfil. Uma imagem
opcional com runtime incluído tem matriz própria de SO, arquitectura e
segurança. Mantém-se a opção de usar um Java 17 externo. A versão Java
suportada também se aplica aos verificadores e launchers distribuídos.

## Opções e consequências

- **Um artefacto por módulo com target 17** é a proposta. Reduz a quantidade
  de artefactos a assinar, verificar e testar. Exige adaptar APIs de colecções
  e dispatch tipado, bem como provar que a gestão de processos conserva os
  limites de tempo, I/O e cancelamento.
- **Manter 21 como mínimo** preservaria o código actual e excluiria o
  consumidor Java 17, incluindo geração offline.
- **Variantes por JDK ou JAR multi-release** aumentariam a matriz de
  resolução, proveniência e rollback. Só considerar uma variante se existir
  um requisito medido que justifique outro ADR.

## Contrato de compatibilidade

- Verificar a API pública e os descritores JVM em relação à versão anterior
  aplicável. Executar um consumidor previamente compilado em 21 com as
  bibliotecas novas em 21 e 25. Para Java 17, compilar o consumidor com o
  target 17. Código do próprio consumidor compilado para 21 continua a
  precisar de uma JVM compatível com esse bytecode.
- Preservar código gerado, SQL, parâmetros, cursores, digests, bytes de
  migrations e semântica de transacções. Qualquer alteração incompatível
  exige decisão explícita e documentação de migração.
- Uma actualização de dependências precisa de manter a baseline de cada
  artefacto e do seu grafo resolvido. Verificar variantes multi-release,
  dependências opcionais nos perfis anunciados e ligação real. Uma
  dependência de build pode ter requisitos superiores quando fica fora do
  runtime, do plugin publicado e do processo de geração no consumidor.
- Certificar combinações concretas de Spring Boot, Gradle, Maven e JDK. O
  mínimo de uma ferramenta externa aplica-se à integração respectiva.
  Começar por Spring Boot 4.1.x, Gradle 9.8.2 e Maven 3.9.16 nas três LTS.
  Provar o Gradle mínimo que vier a ser anunciado. Manter o ownership do
  DataSource pelo Spring e o contrato vigente de transacções.
- Adicionar futuras LTS após lançamento GA e certificação. Versões não LTS
  podem receber testes informativos. Propor qualquer descontinuação num ADR,
  com aviso antecipado, janela de migração e data de fim de suporte, antes
  de elevar o target num lançamento incompatível.

## Segurança, CI e activação

Construir uma vez, verificar os hashes e consumir os mesmos artefactos
na matriz de três JDKs. Verificar o major version até 61 para classes
base entregues e para as variantes seleccionáveis pelo Java 17. Executar
fixtures externas com apenas o JDK da célula disponível ao consumidor.
Manter CodeQL, Dependency Review, verificação estrita de dependências,
testes PostgreSQL reais e gates de distribuição. Os jobs adicionais devem
falhar se uma célula anunciada faltar ou se um teste for ignorado.

O teste de update e rollback deve validar assinatura, versão, plataforma e
JVM mínima antes da activação. Um artefacto antigo com mínimo 21 continua
inelegível numa máquina apenas com 17. Manter a versão activa anterior se a
nova selecção falhar. Provar Windows e Linux nos perfis aprovados, incluindo
JNI, Ed25519, JDBC/TLS e recuperação. Os testes de reinício e confiança de
release mantêm os seus gates próprios.

Consultar a [auditoria e plano de certificação](../development/JAVA_LTS_AUDIT.md).
