# RFC-001: Linguagem de schema Tuprel

- **Estado:** Aceite para o subconjunto implementado na Fase 1
- **Data:** 2026-09-20
- **Âmbito:** `schema.tuprel`, parser, AST, validação, diagnostics e formatter

## 1. Contexto

O `schema.tuprel` é a fonte declarativa principal dos modelos Tuprel. A Fase 1
precisa de um contrato suficientemente explícito para que lexer, parser,
validação e formatter não cristalizem decisões acidentais. Este RFC fixa o
subconjunto inicial da linguagem; não define a API Java gerada (RFC-002),
semântica de carregamento de relações (RFC-003), migrations ou runtime.

O desenho privilegia leitura directa, parsing previsível, diagnostics com
localização e uma evolução que rejeita construções desconhecidas em vez de as
ignorar silenciosamente.

## 2. Objectivos e non-goals

### Objectivos

- representar configuração local, models, enums, tipos, cardinalidade,
  defaults, unicidade, índices e relações declarativas básicas;
- produzir um AST imutável e um modelo validado reutilizável pelo codegen;
- distinguir erros lexicais, sintácticos e semânticos;
- preservar ordem de declarações e membros;
- permitir formatação determinística sem acesso à base de dados;
- manter a implementação JDK-only.

### Non-goals da Fase 1

- imports e schemas distribuídos por vários ficheiros;
- tipos nativos PostgreSQL, arrays PostgreSQL e atributos específicos de
  dialect;
- mapping físico com `@map`/`@@map`, check constraints e índices avançados;
- chaves primárias compostas;
- acções referenciais e regras de cascade;
- inferência de relações, carregamento de relações ou geração de Java;
- compatibilidade retroactiva antes de existir uma release pública.

## 3. Ficheiro e codificação

Um documento é um ficheiro de texto UTF-8, convencionalmente chamado
`schema.tuprel`. A Fase 1 compila um documento de cada vez. Declarações podem
aparecer por qualquer ordem; referências de tipos são resolvidas depois do
parsing, por isso podem apontar para declarações posteriores.

O fim de linha aceite é LF ou CRLF. O formatter produz LF. Um ficheiro vazio é
sintacticamente válido, mas semanticamente inválido porque não declara models.

## 4. Modelo de origem

Cada nó e diagnostic tem um intervalo semiaberto `[start, end)` no texto
original. Offsets contam unidades UTF-16, de acordo com `String` em Java.
Linhas e colunas apresentadas ao utilizador começam em 1; a coluna conta code
points Unicode para não dividir pares surrogate visualmente.

O modelo público contém:

- nome lógico da origem, sem efectuar I/O;
- texto imutável;
- `SourceSpan` com origem, offset inicial e offset final;
- conversão determinística de offset para linha/coluna.

I/O pertence à CLI ou a integrações. O parser recebe texto, não paths.

## 5. Regras lexicais

### 5.1 Identificadores

Identificadores usam a forma ASCII:

```text
[A-Za-z_][A-Za-z0-9_]*
```

ASCII é uma escolha deliberada para o primeiro contrato: os mesmos nomes vão
alimentar Java e SQL, e uma regra pequena evita normalização Unicode ambígua e
confusables. Strings continuam a aceitar Unicode. Mapping de nomes físicos e
uma eventual extensão de identificadores exigem RFC próprio.

Os nomes são case-sensitive. `User` e `user` são distintos.

### 5.2 Keywords

As keywords reservadas da Fase 1 são:

```text
datasource  enum  false  generator  model  true
```

Tipos escalares e nomes de atributos são identificadores validados pelo
contexto. Esta escolha deixa a lista de keywords curta e permite acrescentar
scalars sem alterar o lexer.

### 5.3 Whitespace e comentários

Espaço, tab, CR e LF separam tokens e não têm significado semântico.

São aceites:

```tuprel
// comentário até ao fim da linha

/* comentário
   de bloco */
```

Comentários de bloco não são aninhados. Um comentário de bloco não terminado
produz diagnostic lexical. Comentários não entram no AST semântico; o
formatter preserva-os e não os transforma em documentação Java nesta fase.

### 5.4 Literais

- strings usam aspas duplas;
- escapes suportados: `\\`, `\"`, `\n`, `\r`, `\t` e `\uXXXX`;
- inteiros decimais podem ter sinal `-` e não aceitam separadores;
- `true` e `false` são booleanos;
- um identificador pode ser um valor simbólico, por exemplo um membro de enum;
- chamadas têm nome e argumentos, por exemplo `env("DATABASE_URL")`;
- listas usam `[a, b]`.

Não existem floats literais na Fase 1. Defaults decimais usam inteiros exactos
ou ficam adiados até a representação decimal ser definida.

### 5.5 Pontuação

```text
{ } ( ) [ ] ? , : = @ @@ -
```

Qualquer carácter não reconhecido produz diagnostic lexical e o lexer avança
pelo code point completo para continuar a recolher erros.

## 6. Gramática da Fase 1

A gramática informativa usa EBNF. Quebras de linha não são terminadores; os
limites resultam da estrutura dos membros.

```ebnf
Document          ::= Declaration* EOF ;
Declaration       ::= DatasourceDecl | GeneratorDecl | ModelDecl | EnumDecl ;

DatasourceDecl    ::= "datasource" Identifier "{" ConfigProperty* "}" ;
GeneratorDecl     ::= "generator" Identifier "{" ConfigProperty* "}" ;
ConfigProperty    ::= Identifier "=" Expression ;

ModelDecl         ::= "model" Identifier "{" ModelMember* "}" ;
ModelMember       ::= FieldDecl | BlockAttribute ;
FieldDecl         ::= Identifier TypeRef FieldAttribute* ;
TypeRef           ::= Identifier ("?" | "[" "]")? ;
FieldAttribute    ::= "@" Identifier Arguments? ;
BlockAttribute    ::= "@@" ("index" | "unique") Arguments ;

EnumDecl          ::= "enum" Identifier "{" Identifier* "}" ;

Arguments         ::= "(" (Argument ("," Argument)*)? ")" ;
Argument          ::= (Identifier ":")? Expression ;
Expression        ::= String | Integer | Boolean | Identifier
                    | Call | List ;
Call              ::= Identifier Arguments ;
List              ::= "[" (Expression ("," Expression)*)? "]" ;
```

Nomes de argumentos são obrigatórios em `@relation` e opcionais nas chamadas
gerais do AST para permitir diagnostics precisos. A validação de cada atributo
define a forma aceite.

## 7. Declarações de configuração

O subconjunto inicial aceita:

```tuprel
datasource db {
    provider = "postgresql"
    url = env("DATABASE_URL")
}

generator java {
    package = "com.example.app.db"
}
```

Existe no máximo um `datasource` e um `generator`. O datasource requer
`provider = "postgresql"` e `url = env("NOME")`; uma URL literal é rejeitada
para evitar credenciais no schema. O generator requer `package` com um nome de
package Java válido. Propriedades desconhecidas são erro, não extensões
silenciosas.

Configuração no `tuprel.toml` e precedência entre fontes ficam fora deste RFC.

## 8. Models, fields e cardinalidade

```tuprel
model User {
    id Int @id
    name String
    bio String?
    posts Post[]
}
```

Cada model contém pelo menos um field e exactamente um field `@id` na Fase 1.
Fields preservam a ordem de origem.

Cardinalidade:

| Forma | Significado |
|---|---|
| `T` | valor singular obrigatório / `NOT NULL` |
| `T?` | valor singular opcional / nullable |
| `T[]` | lista não nullable de elementos não nullable |

`T[]?`, `T?[]` e elementos nullable não fazem parte da Fase 1. Uma referência
a model com `[]` representa o lado plural de uma relação; o RFC-003 decidirá
como essa relação é carregada na API Java.

## 9. Tipos escalares

O conjunto da Fase 1 segue a especificação canónica:

```text
String  Boolean  Short  Int  Long  Decimal  Float  Double
UUID  Instant  LocalDateTime  LocalDate  LocalTime  Bytes  Json
```

Um nome de tipo também pode resolver para um enum ou model declarado. Um nome
que não pertença a nenhum destes conjuntos produz `TUPREL-SCHEMA-SEM-004`.

O significado Java/PostgreSQL exacto é consumido por fases posteriores. `Json`
e listas escalares são representáveis no AST, mas não implicam ainda suporte de
runtime PostgreSQL.

## 10. Enums

```tuprel
enum UserStatus {
    ACTIVE
    SUSPENDED
    BLOCKED
}
```

Um enum tem pelo menos um membro. Nomes repetidos são inválidos. Membros de
enum são valores simbólicos e podem ser usados em `@default`.

## 11. Atributos de field

### 11.1 `@id`

Marca a chave primária simples. Só pode aparecer uma vez, num field singular,
obrigatório e escalar. Models sem `@id`, com vários `@id` ou com `@id` numa
relação/lista são inválidos. Chaves compostas ficam adiadas.

### 11.2 `@unique`

Marca unicidade de um field singular escalar ou enum. Pode ser usado num field
nullable; a futura implementação PostgreSQL documentará a semântica concreta
de múltiplos `NULL`.

### 11.3 `@default(expression)`

Aceita um argumento posicional. A Fase 1 valida:

- strings para `String`;
- booleanos para `Boolean`;
- inteiros para os scalars numéricos;
- membro existente para enum;
- `uuid()` para `UUID`;
- `now()` para `Instant` ou `LocalDateTime`;
- `identity()` para `Short`, `Int` ou `Long`.

Defaults em listas, relações e fields opcionais são rejeitados nesta fase.

### 11.4 `@relation(...)`

Uma relação singular pode declarar explicitamente as colunas locais e remotas:

```tuprel
model Post {
    id UUID @id @default(uuid())
    authorId UUID
    author User @relation(fields: [authorId], references: [id])
}
```

`fields` e `references` são listas não vazias de nomes, com o mesmo tamanho.
Os fields locais têm de existir e ser scalars/enums; os fields remotos têm de
existir no model alvo e formar a chave `@id`, um `@unique`, ou exactamente um
`@@unique` composto. Os tipos têm de coincidir. Para relações singulares, a
nulabilidade da relação e das colunas locais tem de ser coerente. Relações de
lista são o lado inverso e não levam `fields`/`references` na Fase 1.

Acções referenciais, nomes de relação e inferência de relações ficam adiados.

Um atributo desconhecido, repetido ou aplicado a uma categoria incompatível
é erro. Isto evita que uma versão antiga aceite e ignore semântica nova.

## 12. Atributos de model

```tuprel
model User {
    id UUID @id
    tenantId UUID
    email String
    createdAt Instant

    @@unique([tenantId, email])
    @@index([tenantId, createdAt])
}
```

`@@unique` e `@@index` recebem uma lista posicional, não vazia, de fields
existentes, sem duplicados. O mesmo conjunto não pode ser declarado duas vezes
para o mesmo atributo. Fields de relação não podem participar nestas listas.

`@@id`, `@@map`, `@@check`, opções de ordenação e opções específicas de
PostgreSQL ficam adiados.

## 13. Validação semântica

Parsing e validação são fases separadas. O validator recolhe diagnostics em
ordem de origem e valida pelo menos:

- documento com pelo menos um model;
- nomes de declarações de tipo únicos;
- nomes de fields e membros de enum únicos;
- identificadores que colidam com keywords Java ou nomes reservados da DSL;
- tipos conhecidos;
- presença e validade da chave `@id` simples;
- atributos conhecidos, únicos, com aridade e categoria correctas;
- compatibilidade dos defaults;
- fields de `@@index`/`@@unique` existentes e não repetidos;
- configuração obrigatória e sem propriedades desconhecidas;
- alvos, fields, references, tipos e nulabilidade de relações explícitas.

Ciclos entre models são permitidos. Ciclos de cascade não existem neste
subconjunto porque as acções referenciais ainda não são aceites.

## 14. Diagnostics

Um diagnostic público contém código estável, severidade, mensagem e
`SourceSpan`. A Fase 1 usa `ERROR`; `WARNING` fica disponível para evolução sem
ser usado para aceitar schema ambíguo.

Famílias de códigos:

| Prefixo | Categoria |
|---|---|
| `TUPREL-SCHEMA-LEX-` | caracteres, strings, escapes e comentários |
| `TUPREL-SCHEMA-PARSE-` | estrutura gramatical e recuperação |
| `TUPREL-SCHEMA-SEM-` | nomes, tipos, atributos e relações |

Exemplos:

```text
TUPREL-SCHEMA-LEX-001  Unexpected character '$'.
TUPREL-SCHEMA-PARSE-002  Expected model name after 'model'.
TUPREL-SCHEMA-SEM-002  Model 'User' declares field 'id' more than once.
TUPREL-SCHEMA-SEM-004  Unknown type 'Strng' for field 'User.name'.
TUPREL-SCHEMA-SEM-016  Relation 'Post.author' references unknown field 'User.key'.
```

Input inválido esperado não lança uma exception como resultado normal. A API
de compilação devolve AST quando o parsing recuperou, modelo validado apenas
sem erros, e a lista imutável de diagnostics. Argumentos Java nulos e violações
do contrato de chamada continuam a ser erros de programação.

O renderer humano é separado do diagnostic estruturado e pode mostrar
`ficheiro:linha:coluna`, trecho e caret sem alterar o contrato do diagnostic.

## 15. Recuperação de parsing

O parser é descendente recursivo e explícito. Depois de um erro de topo,
sincroniza na próxima keyword de declaração ou EOF. Dentro de model/enums e
configuração, sincroniza no próximo membro reconhecível ou `}`. A recuperação
tem de consumir pelo menos um token, evitando loops em input malformado.

Diagnostics lexicais não são descartados pelo parser. A validação semântica só
corre quando não existem erros lexicais ou sintácticos, para não produzir uma
cascata enganadora sobre AST incompleto.

## 16. Formatter

`tuprel format`:

- usa quatro espaços por nível e LF;
- remove trailing whitespace e termina com newline;
- normaliza espaços entre tokens, junto de pontuação, `=` e `:`;
- mantém ordem de declarações, fields, enum members e atributos;
- preserva comentários e no máximo uma linha vazia consecutiva;
- é idempotente;
- recusa escrever quando existem diagnostics de lexer/parser/semântica.

O formatter não alinha colunas com padding, porque uma alteração num nome não
deve produzir diff em todas as linhas vizinhas. Não ordena declarações nem
membros.

## 17. CLI inicial

A Fase 1 fornece um adapter CLI separado que orquestra a API de schema:

```text
tuprel validate [schema.tuprel]
tuprel format [--check] [schema.tuprel]
```

Sem path, o default é `tuprel/schema.tuprel`. `validate` não acede à base de
dados. `format` escreve apenas o ficheiro explicitamente resolvido; `--check`
não escreve e falha quando o conteúdo não está formatado. Exit codes:

| Código | Significado |
|---|---|
| 0 | sucesso |
| 1 | schema inválido ou não formatado em `--check` |
| 2 | uso inválido ou falha de I/O |

A CLI não lê `.env`, não abre ligações e não mostra conteúdo de variáveis de
ambiente.

## 18. API e boundaries

`tuprel-schema` expõe apenas source model, AST, validated model, diagnostics,
compiler/validator, formatter e rendering humano. Lexer e parser concretos são
package-private. Não depende de JDBC, PostgreSQL, Spring ou tooling Gradle.

O adapter CLI vive no boundary `tuprel-cli` e depende de `tuprel-schema`; não
duplica parsing ou validação. Este módulo começa pequeno na Fase 1 e crescerá
incrementalmente, conforme o catálogo de módulos.

## 19. Evolução e compatibilidade

Antes de 1.0, alterações ainda podem ser incompatíveis, mas têm de actualizar
este RFC, fixtures e changelog. Depois de existir um contrato versionado:

- construções desconhecidas continuam a falhar;
- syntax nova deve evitar reinterpretar documentos antigos;
- keywords novas precisam de análise de colisões;
- diagnostics mantêm códigos quando o significado permanece;
- extensões de dialect usam namespaces/atributos explícitos;
- um futuro `formatVersion` será introduzido antes de estabilidade 1.0, não
  inventado nesta fase.

## 20. Revisão interna

### Conformidade

- **Especificação canónica:** mantém a sintaxe conceptual publicada, o conjunto
  inicial de scalars, nullable/list, enums, defaults, índices e relações.
- **Plano da Fase 1:** cobre configuração, lexer/parser, AST, diagnostics,
  validator, formatter e comandos iniciais.
- **Codegen futuro:** nomes e cardinalidade são explícitos; o validated model
  não expõe tokens nem estado mutável do parser.
- **Modelo relacional:** ids, unique, índices, foreign keys e references são
  estruturais e validados localmente.
- **Manutenção do parser:** gramática pequena, sem parser framework nem
  ambiguidades dependentes de whitespace.
- **Evolução:** desconhecidos falham e o subconjunto adiado não ganha semântica
  acidental.

### Decisões deliberadamente adiadas

- schema modular/imports;
- mapping físico e naming strategies;
- composite primary keys e check constraints;
- defaults decimais/fraccionários e expressões nativas;
- relações implícitas, nomes de relação e acções referenciais;
- comentários como documentação do AST;
- versionamento estável do formato.

### Questões por resolver

Não existe uma questão incompatível que bloqueie o subconjunto da Fase 1. As
decisões adiadas têm boundaries explícitos e não precisam de ser pressupostas
para implementar o pipeline actual.
