# RFC-002: API Java gerada para o subconjunto da Fase 2

- **Estado:** Aceite para o subconjunto implementado na Fase 2
- **Data:** 2026-09-22
- **Âmbito:** tipos Java gerados, metadata, inputs e contrato de geração

## 1. Fronteira da decisão

O `schema.tuprel` é compilado e validado pelo RFC-001 antes da geração. A Fase 2
produz tipos Java que descrevem models e permitem construir inputs tipados.
Ainda não existe um cliente que abra connections, execute queries ou carregue
relações. O nome `TuprelClient` e os seus métodos operacionais só se tornam
contrato quando o runtime da Fase 3 os puder cumprir. O ponto de entrada gerado
nesta fase é `TuprelSchema`, que expõe descritores tipados de models, não I/O.

A Fase 3 acrescenta um runtime PostgreSQL de baixo nível com mapper tipado,
sem alterar estes tipos gerados. Não transforma `TuprelSchema` num cliente
operacional nem aprova ainda a forma final de `TuprelClient`.

Isto concretiza o mínimo de *client/model entry points* do plano sem prometer
CRUD ou uma query API antes das respectivas fases. Os exemplos de `db.user()`
na especificação de produto continuam direccionais até esses contratos serem
implementados.

## 2. Entrada e API pública do gerador

`tuprel-codegen-java` depende de `tuprel-schema` e recebe exclusivamente
`ValidatedSchema`. Não faz parsing novamente nem lê ficheiros durante o
rendering. `JavaCodeGenerator.generate(ValidatedSchema)` devolve um resultado
com ficheiros relativos e diagnostics estruturados; um schema inválido nunca
deve ser passado ao gerador. A declaração `generator java` com `package` é
obrigatória para gerar, embora o RFC-001 permita schemas válidos sem generator.

Os contratos públicos do módulo limitam-se à entrada de geração, ao resultado
imutável e à escrita/verificação de ficheiros. Naming, IR, rendering e gestão
do manifesto são detalhes internos. Não se cria uma API pública de plugins do
gerador nesta fase.

## 3. Packages, nomes e colisões

O valor validado de `generator java.package` é o package raiz `P`. A saída
JDK-only usa `P.model`, `P.create`, `P.update`, `P.fields` e `P.where`, com
tipos de suporte e `TuprelSchema` em `P`. Um `model User` produz `User`,
`UserCreate`, `UserUpdate`, `UserFields` e `UserWhere`; um `enum Role` produz
`Role` em `P.model`. Nomes de schema e fields mantêm a grafia original; não há
conversão implícita para camelCase/PascalCase nem de nomes SQL físicos.

O RFC-001 já rejeita keywords Java. O gerador rejeita também nomes que colidam
com os tipos ou métodos gerados, imports necessários, membros herdados de
`Object` ou paths equivalentes em sistemas case-insensitive. Não renomeia
silenciosamente um identificador: devolve `TUPREL-CODEGEN-...` com a localização
do nome e uma mensagem accionável. A mesma regra cobre enums, models, nomes
derivados e package segments. O namespace reservado `java.*` e nomes de
dispositivo incompatíveis com ficheiros Windows são recusados. Nomes não podem
determinar paths fora do output root nem injectar texto Java estrutural.

## 4. Mapping de tipos

| Scalar do schema | Java singular | Observação |
|---|---|---|
| `String` | `String` | valor textual |
| `Boolean` | `boolean` / `Boolean` | boxed se opcional ou em input |
| `Short` | `short` / `Short` | boxed se opcional ou em input |
| `Int` | `int` / `Integer` | boxed se opcional ou em input |
| `Long` | `long` / `Long` | boxed se opcional ou em input |
| `Decimal` | `BigDecimal` | sem conversão para `double` |
| `Float` | `float` / `Float` | boxed se opcional ou em input |
| `Double` | `double` / `Double` | boxed se opcional ou em input |
| `UUID` | `UUID` | `java.util.UUID` |
| `Instant` | `Instant` | `java.time.Instant` |
| `LocalDateTime` | `LocalDateTime` | `java.time.LocalDateTime` |
| `LocalDate` | `LocalDate` | `java.time.LocalDate` |
| `LocalTime` | `LocalTime` | `java.time.LocalTime` |
| `Bytes` | `TuprelBytes` | cópia defensiva de `byte[]`, igualdade por conteúdo |
| `Json` | `TuprelJson` | portador imutável de texto JSON; parsing fica adiado |

`TuprelBytes` e `TuprelJson` são tipos JDK-only gerados no package raiz. O
primeiro evita expor arrays mutáveis numa API de valores; o segundo evita
introduzir uma biblioteca JSON ou confundir JSON com uma `String` comum.
Nenhum dos dois implica suporte de binding PostgreSQL nesta fase.

Um enum validado torna-se um `enum` Java no mesmo package dos models. Um field
`T?` usa a forma boxed ou uma referência nullable; `T[]` usa `List<T>` não
nullable, com elementos não nullable e cópia imutável. Não se gera `Optional`
em cada field nem se introduzem annotations de nullability sem política e
dependência aprovadas. `null` num field opcional representa exactamente o
estado nullable do schema. Os construtores dos modelos rejeitam `null` em
referências obrigatórias; listas são copiadas e rejeitam elementos nulos.

## 5. Models, metadata e inputs

Cada model gera um valor imutável em `P.model` com apenas fields escalares e
enum, na ordem do schema. Relações não surgem como valores carregados dentro
desse valor, evitando ciclos de objectos, I/O implícito e uma promessa de
loading ainda não definida. Relações aparecem estruturalmente em `P.fields`
com nome, target e cardinalidade, para que o código gerado não perca essa
informação. `P.where` fornece apenas acessores tipados aos descritores de
fields; operadores, filtros executáveis e SQL pertencem a fases posteriores.

`TuprelSchema` expõe descritores estáticos `TuprelModel<Model, Create, Update>`
por model. Estes descritores não executam nada. Metadata preserva nomes e
cardinalidades declarados; não inventa nomes físicos de tabela/coluna.

`P.create.UserCreate` e `P.update.UserUpdate` são inputs imutáveis construídos
por builders tipados. Um valor de field transporta presença e valor
separadamente: ausente, presente com valor, ou presente com `null` quando o
field é opcional. Isto permite distinguir omissão de `NULL` sem `Object`, casts
ou `Optional<Optional<T>>`. Create exige fields obrigatórios sem `@default`;
fields com default podem ficar ausentes. Update exclui `@id` e não obriga a
preencher fields. Relações não são escritas por estes inputs na Fase 2.
Defaults são metadata declarativa; nem o gerador nem o input os executam.

## 6. Imports, visibilidade e compatibilidade

O output usa Java 21, UTF-8, LF, imports explícitos ordenados e nunca imports
wildcard. Não usa `var` nos exemplos públicos. Tipos gerados públicos são os
valores, enums, inputs, metadata/descritores e `TuprelSchema`. Helpers de
rendering não são gerados nem expostos. Código gerado compila apenas com o JDK
21; não depende de Tuprel runtime, Spring, JPA ou Hibernate.

Nomes, packages, assinaturas e semântica de nulabilidade gerados são contratos
de source compatibility. Uma futura mudança incompatível exige revisão do RFC
e política de versionamento; ainda não há promessa de compatibilidade binária
entre releases públicas, porque não existe release publicada.

## 7. Localização e regeneração

Por omissão, a CLI escreve sob
`build/generated/sources/tuprel/main`, seguindo ADR-0007. Nenhum ficheiro vai
para `src/main/java`. Cada `.java` tem um header estável de geração, sem
timestamp, path da máquina ou identificador aleatório.

O renderer devolve um mapa imutável de paths relativos (separador `/`) para
conteúdo. Para o mesmo schema validado e versão do gerador, paths e bytes são
idênticos. A escrita valida todos os paths, rejeita `..`, paths absolutos,
symlinks e colisões case-insensitive antes de alterar ficheiros. Um manifesto
sob o output root regista apenas os ficheiros que Tuprel possui e os seus
hashes. Uma regeneração só substitui/remove ficheiros previamente registados
se os seus bytes ainda corresponderem ao manifesto; nunca apaga ficheiros
externos ou edições manuais. Uma colisão ou edição manual falha claramente.
`tuprel generate --check` compara sem escrever.

## 8. CLI e diagnostics

`tuprel generate [--check] [schema.tuprel]` usa o mesmo schema default dos
comandos da Fase 1. Lê UTF-8, compila, apresenta diagnostics da Fase 1,
gera apenas quando há modelo validado e escreve no output root gerado. Exit
codes: `0` para sucesso; `1` para schema inválido, geração impossível ou
diferenças em `--check`; `2` para uso inválido ou falha de I/O. A mensagem de
erro não imprime connection strings nem valores de `env(...)`.

## 9. Testes e limites

Golden tests revistos cobrem tipos, imports, nomes e formato. Um teste real
compila com `javac --release 21 -Xlint:all -Werror` o Java produzido após
parsing/validação de um schema, sem Tuprel runtime. Testes de determinismo
comparam paths e bytes em gerações repetidas. Testes de escrita cobrem
travessia, symlinks, colisões, edições manuais e limpeza segura.

Ficam adiados: `TuprelClient` operacional, JDBC, bindings PostgreSQL,
queries, filtros executáveis, relation loading (RFC-003), migrations,
integrações Gradle/Maven para projectos consumidores, annotations de
nullability/NullAway e distribuição/publicação de artefactos.

## 10. Revisão interna de design

O RFC-001 fixa cardinalidade, tipos e configuração; este RFC apenas mapeia o
modelo validado para Java. A especificação de produto orienta a forma geral,
mas os seus exemplos de runtime são futuros. A separação entre values e
metadata de relações preserva o gate do RFC-003. O código JDK-only continua
framework-independent e compilável antes de existir runtime. O manifest e
os nomes ASCII validados preservam reprodutibilidade e confinamento.

Não há uma decisão pública incompatível por resolver para o subconjunto aqui
definido. As decisões de execução e loading continuam explicitamente adiadas.
