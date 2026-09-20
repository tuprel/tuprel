# Tuprel ORM

> Nota de documentação: os exemplos Java usam tipos explícitos por omissão. Nomes de tipos em funcionalidades ainda não implementadas são contratos propostos e devem ser confirmados na fase/ADR correspondente.

## Documento de Visão, Produto, Arquitectura e Especificação Técnica

**Estado:** proposta de arquitectura para desenvolvimento  
**Nome do produto:** Tuprel ORM  
**Comando principal:** `tuprel`  
**Ecossistema:** Java  
**Primeira base de dados alvo:** PostgreSQL  
**Requisito mínimo proposto:** Java 21  
**Objectivo da primeira versão estável:** Tuprel ORM 1.0

---

## 1. Resumo executivo

O Tuprel ORM é uma plataforma moderna de persistência de dados para Java, concebida para tornar o acesso a bases de dados relacionais mais simples, previsível, seguro e produtivo, sem esconder o funcionamento fundamental da base de dados.

O produto não deverá ser apenas uma pequena biblioteca de mapeamento objecto-relacional. O objectivo é oferecer um conjunto coerente de ferramentas para todo o ciclo de vida da persistência de uma aplicação Java:

- definição do modelo de dados;
- validação do schema;
- geração de código Java type-safe;
- criação e evolução da base de dados através de migrações;
- consultas, filtros, ordenação e paginação;
- criação, actualização e eliminação de registos;
- relações entre modelos;
- transacções;
- operações em lote;
- introspecção de bases de dados existentes;
- visualização e edição de dados em ambiente de desenvolvimento;
- integração directa com Spring Boot;
- integração com Maven e Gradle;
- suporte a observabilidade, métricas e diagnóstico de queries;
- ferramentas de testes;
- execução previsível, sem carregamentos escondidos ou alterações silenciosas.

A experiência desejada é esta:

```text
schema simples
      ↓
validação
      ↓
geração de código Java
      ↓
migração da base de dados
      ↓
cliente type-safe
      ↓
queries previsíveis
      ↓
aplicação Java
```

O Tuprel deverá reduzir a quantidade de código repetitivo que normalmente existe na camada de dados sem retirar ao programador o controlo sobre SQL, transacções, índices, constraints e performance.

A promessa central do produto é:

> Definir os dados uma vez, gerar uma API Java segura e trabalhar com a base de dados através de uma experiência consistente do desenvolvimento local até produção.

---

# 2. O problema que o Tuprel vem resolver

## 2.1 Fragmentação da camada de persistência

Num projecto Java moderno, é comum a persistência ficar distribuída por várias tecnologias e conceitos diferentes:

```text
Entidades Java
Repositories
ORM
Migrações
Driver JDBC
Connection pool
Configuração da framework
Queries personalizadas
Ferramentas de debugging
Scripts de seed
Ferramentas externas de base de dados
```

Cada componente pode ser bom individualmente, mas o programador é obrigado a aprender, configurar e manter vários mecanismos ao mesmo tempo.

O Tuprel pretende oferecer uma experiência integrada.

```text
Tuprel
├── Schema
├── Code Generator
├── Query Client
├── Runtime
├── Migration Engine
├── Introspection
├── Seed
├── Studio
├── CLI
├── Gradle Plugin
├── Maven Plugin
└── Spring Boot Integration
```

---

## 2.2 Excesso de boilerplate

Uma operação simples não deveria obrigar o developer a criar uma grande quantidade de código cerimonial.

O Tuprel deverá evitar que um modelo simples obrigue o developer a repetir a mesma informação em vários locais.

Exemplo conceptual:

```tuprel
model User {
    id        UUID      @id @default(uuid())
    name      String
    email     String    @unique
    active    Boolean   @default(true)
    createdAt Instant   @default(now())
    updatedAt Instant   @updatedAt
}
```

A partir desta definição, o Tuprel pode conhecer:

- o nome do modelo;
- a tabela correspondente;
- os tipos Java;
- os tipos SQL;
- a chave primária;
- os campos obrigatórios;
- os campos opcionais;
- os valores por omissão;
- os índices;
- as constraints;
- os campos permitidos na criação;
- os campos permitidos na actualização;
- os filtros possíveis;
- os operadores válidos para cada tipo;
- o mapeamento necessário entre resultado SQL e objecto Java.

Essa informação não deve ser reescrita manualmente em múltiplos ficheiros.

---

## 2.3 Erros descobertos demasiado tarde

Muitos erros de persistência só aparecem em runtime:

- nome de coluna errado;
- tipo incompatível;
- filtro inválido;
- campo inexistente;
- ordenação sobre campo não suportado;
- relação mal configurada;
- valor obrigatório em falta;
- query construída através de strings incorrectas.

O Tuprel deverá deslocar o máximo possível desses erros para:

1. validação do schema;
2. geração de código;
3. compilação Java;
4. validação de migrações;
5. testes automatizados.

O objectivo não é afirmar que todos os problemas de base de dados podem ser detectados antes de runtime. Isso seria irrealista. O objectivo é reduzir de forma significativa a classe de erros que podem ser detectados mais cedo.

---

## 2.4 Curva de aprendizagem elevada

Um developer Java deve conseguir criar uma aplicação pequena sem ser obrigado a dominar imediatamente:

- gestão de estado de entidades;
- proxies;
- lazy loading implícito;
- dirty checking;
- dezenas de annotations;
- detalhes internos de uma framework de persistência;
- múltiplas ferramentas de migração.

O Tuprel deverá começar com uma API pequena e explícita.

```java
List<User> users = db.user()
    .findMany(q -> q
        .where(UserWhere.active().eq(true))
        .orderBy(UserOrder.createdAt().desc())
        .take(20)
    );
```

O developer pode aprofundar os conceitos de base de dados à medida que precisa deles, sem que o Tuprel esconda o SQL e o comportamento real.

---

## 2.5 Comportamentos implícitos difíceis de diagnosticar

O Tuprel deverá evitar por omissão:

- lazy loading invisível;
- queries adicionais disparadas por um getter;
- actualizações automáticas inesperadas;
- cascatas destrutivas não explícitas;
- N+1 queries provocadas sem aviso;
- alteração de objectos em memória que provoque escrita automática na base de dados.

A regra é:

> Ler deve ser explícito. Escrever deve ser explícito. Relações devem ser explícitas. Transacções devem ser previsíveis.

---

# 3. Visão do produto

O Tuprel deverá tornar-se uma camada de persistência Java que um developer possa escolher tanto para uma API pequena como para uma aplicação empresarial, mantendo a mesma experiência conceptual.

A visão é permitir que um projecto comece assim:

```bash
tuprel init
```

E evolua até produção sem ter de substituir a camada de dados quando a aplicação crescer.

O mesmo produto deverá servir:

- APIs REST;
- aplicações Spring Boot;
- serviços backend;
- aplicações modulares;
- microserviços;
- aplicações CLI;
- workers;
- aplicações Java sem framework;
- aplicações executadas com virtual threads;
- aplicações empacotadas como native image, quando tecnicamente suportado.

---

# 4. Princípios fundamentais

## 4.1 Schema como fonte principal de verdade

O ficheiro de schema descreve a estrutura lógica da aplicação.

O código Java gerado, as migrações e o metadata interno derivam dessa definição.

---

## 4.2 Type safety primeiro

Sempre que possível, uma query inválida deve falhar durante compilação ou geração, e não apenas em produção.

---

## 4.3 Sem magia escondida

O Tuprel pode automatizar trabalho repetitivo, mas não deverá executar operações importantes de forma invisível.

---

## 4.4 SQL continua a ser importante

O Tuprel não deverá tratar SQL como algo que deve ser escondido do developer.

O produto deverá permitir:

- visualizar SQL gerado;
- registar SQL em logs;
- executar SQL raw quando necessário;
- analisar planos de execução;
- editar migrações antes de serem aplicadas;
- perceber exactamente o que vai acontecer.

---

## 4.5 Bom por omissão, configurável quando necessário

O developer deverá conseguir começar com quase nenhuma configuração.

Casos avançados devem ser possíveis sem transformar a experiência básica numa configuração complexa.

---

## 4.6 Framework-agnostic no núcleo

O Tuprel Core não deverá depender de Spring Boot.

Spring Boot será uma integração de primeira classe, mas o motor deve funcionar sozinho.

---

## 4.7 Código gerado em vez de reflection no caminho crítico

A arquitectura deverá privilegiar código gerado e metadata compilado.

O objectivo é evitar reflection repetitiva durante cada query e facilitar:

- performance previsível;
- arranque rápido;
- suporte a native image;
- melhor integração com IDE;
- erros detectados mais cedo.

---

## 4.8 PostgreSQL primeiro

A primeira versão estável deverá fazer PostgreSQL muito bem em vez de suportar várias bases de dados de forma incompleta.

Outros dialectos podem ser adicionados posteriormente através de uma SPI de dialectos.

---

# 5. O que o Tuprel não deve ser

O Tuprel 1.0 não deverá tentar ser tudo ao mesmo tempo.

Não é objectivo inicial:

- substituir a base de dados;
- esconder SQL completamente;
- implementar uma base de dados própria;
- oferecer uma linguagem de query independente de SQL para todos os casos imagináveis;
- suportar todas as bases de dados na primeira versão;
- implementar cache distribuída;
- implementar event sourcing;
- implementar filas de mensagens;
- gerir autenticação da aplicação;
- ser uma framework web;
- transformar o ORM num framework monolítico obrigatório.

---

# 6. Público-alvo

## 6.1 Developer Java iniciante ou intermédio

Precisa de uma forma clara de trabalhar com dados sem configurar demasiadas camadas.

## 6.2 Developer Spring Boot

Quer produtividade, integração com `@Transactional`, métricas e um cliente de dados previsível.

## 6.3 Equipas de produto

Querem:

- migrações versionadas;
- desenvolvimento rápido;
- menor repetição;
- consistência entre developers;
- workflows CI/CD previsíveis.

## 6.4 Equipas de backend experientes

Precisam de:

- queries explícitas;
- SQL observável;
- transacções;
- operações batch;
- cursor pagination;
- optimistic locking;
- explain plans;
- raw SQL como escape hatch;
- performance previsível.

## 6.5 Projectos existentes

O `tuprel db pull` deverá permitir adoptar Tuprel a partir de uma base de dados PostgreSQL já existente.

---

# 7. Identidade e terminologia

## 7.1 Nome

O nome proposto é **Tuprel ORM**.

Descrição preferencial: *Modern relational data toolkit for Java*.

O nome deriva conceptualmente de **TUP**le + **REL**ation, os dois conceitos centrais do modelo relacional.

A CLI é:

```bash
tuprel
```

Os artefactos Maven propostos podem utilizar:

```text
dev.tuprel:tuprel-core
dev.tuprel:tuprel-runtime
dev.tuprel:tuprel-postgresql
dev.tuprel:tuprel-spring-boot-starter
```

Os nomes finais de domínio, groupId e packages devem ser confirmados antes da publicação pública.

---

## 7.2 Conceitos principais

**Schema:** definição declarativa dos modelos e relações.  
**Model:** estrutura lógica persistida.  
**Field:** propriedade escalar ou relação.  
**Client:** ponto de entrada para queries.  
**Delegate:** API gerada para um modelo, por exemplo `db.user()`.  
**Migration:** alteração versionada à estrutura da base de dados.  
**Dialect:** implementação específica de uma base de dados.  
**Generator:** componente que produz código Java.  
**Studio:** interface web local para explorar dados e schema.

---

# 8. Experiência inicial

## 8.1 Criar projecto Spring Boot

O developer cria normalmente o projecto Java.

Estrutura inicial:

```text
my-app/
├── src/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

Depois executa:

```bash
tuprel init
```

Resultado:

```text
my-app/
├── tuprel/
│   └── schema.tuprel
├── src/
├── build.gradle.kts
├── settings.gradle.kts
├── tuprel.toml
└── README.md
```

---

# 9. Estrutura recomendada num projecto Gradle

```text
my-app/
│
├── tuprel/
│   ├── schema.tuprel
│   ├── migrations/
│   │   ├── 20260919_001_init/
│   │   │   └── migration.sql
│   │   └── 20260923_002_add_orders/
│   │       └── migration.sql
│   └── migration.lock
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/app/
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│
├── build/
│   └── generated/
│       └── sources/
│           └── tuprel/
│               └── main/
│
├── build.gradle.kts
├── settings.gradle.kts
├── tuprel.toml
├── .env
├── .gitignore
└── README.md
```

A pasta `build/generated` não deve ser a fonte de verdade e, por omissão, não precisa de ser versionada.

O schema e as migrações devem ser versionados.

---

# 10. Estrutura recomendada num projecto Maven

```text
my-app/
├── tuprel/
│   ├── schema.tuprel
│   └── migrations/
├── src/
│   ├── main/java/
│   ├── main/resources/
│   └── test/java/
├── target/
│   └── generated-sources/
│       └── tuprel/
├── pom.xml
└── tuprel.toml
```

---

# 11. Configuração do projecto

Exemplo de `tuprel.toml`:

```toml
schema = "tuprel/schema.tuprel"

[generator]
package = "com.example.app.db"

[database]
provider = "postgresql"
urlEnv = "DATABASE_URL"

[migrations]
directory = "tuprel/migrations"

[logging]
queries = false
slowQueryThresholdMs = 500
```

Segredos não devem ser armazenados no ficheiro.

A configuração deverá referenciar variáveis de ambiente.

---

# 12. Linguagem de schema

## 12.1 Exemplo básico

```tuprel
datasource db {
    provider = "postgresql"
    url      = env("DATABASE_URL")
}

generator java {
    package = "com.example.app.db"
}

model User {
    id        UUID      @id @default(uuid())
    name      String
    email     String    @unique
    active    Boolean   @default(true)
    createdAt Instant   @default(now())
    updatedAt Instant   @updatedAt
}
```

---

## 12.2 Tipos escalares propostos

A primeira versão deverá suportar pelo menos:

| Tipo Tuprel | Java gerado | PostgreSQL padrão |
|---|---|---|
| `String` | `String` | `VARCHAR` ou `TEXT` |
| `Boolean` | `boolean` / `Boolean` | `BOOLEAN` |
| `Short` | `short` / `Short` | `SMALLINT` |
| `Int` | `int` / `Integer` | `INTEGER` |
| `Long` | `long` / `Long` | `BIGINT` |
| `Decimal` | `BigDecimal` | `NUMERIC` |
| `Float` | `float` / `Float` | `REAL` |
| `Double` | `double` / `Double` | `DOUBLE PRECISION` |
| `UUID` | `UUID` | `UUID` |
| `Instant` | `Instant` | `TIMESTAMPTZ` |
| `LocalDateTime` | `LocalDateTime` | `TIMESTAMP` |
| `LocalDate` | `LocalDate` | `DATE` |
| `LocalTime` | `LocalTime` | `TIME` |
| `Bytes` | `byte[]` | `BYTEA` |
| `Json` | `TuprelJson` ou tipo configurável | `JSONB` |

Tipos nativos PostgreSQL devem poder ser seleccionados através de atributos específicos quando necessário.

---

## 12.3 Campos opcionais

```tuprel
model User {
    id        UUID    @id @default(uuid())
    name      String
    bio       String?
    avatarUrl String?
}
```

O `?` representa nulabilidade na base de dados.

---

## 12.4 Enums

```tuprel
enum UserStatus {
    ACTIVE
    SUSPENDED
    BLOCKED
}

model User {
    id     UUID       @id @default(uuid())
    status UserStatus @default(ACTIVE)
}
```

O generator cria um enum Java correspondente.

---

## 12.5 Valores por omissão

```tuprel
id        UUID    @id @default(uuid())
active    Boolean @default(true)
createdAt Instant @default(now())
count     Int     @default(0)
```

Deverá existir uma forma controlada de expressar defaults nativos da base de dados para casos avançados.

---

## 12.6 Mapeamento de nomes

```tuprel
model User {
    id        UUID    @id @default(uuid())
    firstName String  @map("first_name")

    @@map("users")
}
```

O código Java pode manter camelCase e a base de dados usar snake_case.

---

## 12.7 Índices

```tuprel
model User {
    id        UUID    @id @default(uuid())
    email     String
    tenantId  UUID
    createdAt Instant

    @@unique([tenantId, email])
    @@index([tenantId, createdAt])
}
```

Para PostgreSQL, versões futuras da DSL podem expor:

- índices parciais;
- índices GIN;
- índices GiST;
- expressão de índice;
- INCLUDE columns;
- operador de índice.

A API deve permitir evolução sem quebrar schemas antigos.

---

## 12.8 Chaves compostas

```tuprel
model Membership {
    userId UUID
    teamId UUID
    role   String

    @@id([userId, teamId])
}
```

---

## 12.9 Constraints de check

```tuprel
model Product {
    id    UUID    @id @default(uuid())
    price Decimal
    stock Int

    @@check("price >= 0")
    @@check("stock >= 0")
}
```

Deverá existir uma representação mais estruturada no futuro, mas SQL explícito é uma boa escape hatch inicial para constraints avançadas.

---

# 13. Relações

## 13.1 Um para muitos

```tuprel
model User {
    id       UUID      @id @default(uuid())
    name     String
    products Product[]
}

model Product {
    id       UUID @id @default(uuid())
    name     String

    seller   User @relation(fields: [sellerId], references: [id])
    sellerId UUID
}
```

---

## 13.2 Um para um

```tuprel
model User {
    id      UUID     @id @default(uuid())
    profile Profile?
}

model Profile {
    id     UUID @id @default(uuid())
    user   User @relation(fields: [userId], references: [id])
    userId UUID @unique
}
```

---

## 13.3 Muitos para muitos

O Tuprel deverá suportar duas abordagens.

### Relação implícita

Adequada quando a tabela intermédia não tem dados próprios.

### Relação explícita

Preferível quando a relação tem atributos.

```tuprel
model User {
    id          UUID         @id @default(uuid())
    memberships Membership[]
}

model Team {
    id          UUID         @id @default(uuid())
    memberships Membership[]
}

model Membership {
    userId    UUID
    teamId    UUID
    role      String
    createdAt Instant @default(now())

    user User @relation(fields: [userId], references: [id])
    team Team @relation(fields: [teamId], references: [id])

    @@id([userId, teamId])
}
```

---

## 13.4 Acções referenciais

```tuprel
user User @relation(
    fields: [userId],
    references: [id],
    onDelete: Cascade,
    onUpdate: Restrict
)
```

Valores propostos:

- `Cascade`
- `Restrict`
- `SetNull`
- `NoAction`

As acções destrutivas deverão ser mostradas claramente durante a criação de migrações.

---

# 14. Geração de código

Comando:

```bash
tuprel generate
```

O generator lê o schema, valida-o e produz código Java.

Exemplo de saída Gradle:

```text
build/generated/sources/tuprel/main/com/example/app/db/
├── TuprelClient.java
├── model/
│   ├── User.java
│   └── Product.java
├── create/
│   ├── UserCreate.java
│   └── ProductCreate.java
├── update/
│   ├── UserUpdate.java
│   └── ProductUpdate.java
├── where/
│   ├── UserWhere.java
│   └── ProductWhere.java
├── order/
│   ├── UserOrder.java
│   └── ProductOrder.java
├── include/
│   ├── UserInclude.java
│   └── ProductInclude.java
├── fields/
│   ├── UserFields.java
│   └── ProductFields.java
└── metadata/
    ├── UserMetadata.java
    └── ProductMetadata.java
```

---

# 15. Regras para código gerado

O código gerado deverá ser:

- determinístico;
- formatado;
- compilável sem intervenção manual;
- marcado como generated source;
- estável entre execuções quando o schema não muda;
- adequado a autocomplete da IDE;
- separado do código escrito pelo developer;
- regenerável a qualquer momento;
- livre de timestamps ou conteúdo aleatório que provoque diffs desnecessários.

O comando deverá oferecer:

```bash
tuprel generate --check
```

Em CI, este modo valida que o código gerado corresponde ao schema sem modificar ficheiros.

Também poderá existir:

```bash
tuprel generate --watch
```

para desenvolvimento local.

---

# 16. Modelo de runtime

O Tuprel não deverá utilizar um Entity Manager global nem depender de proxies de entidades.

A proposta é uma API Data Mapper com operações explícitas.

```java
TuprelClient db = ...;
```

Depois:

```java
db.user()
db.product()
db.order()
```

Cada método é gerado a partir do schema.

---

# 17. Criação de registos

Exemplo:

```java
User user = db.user().create(data -> data
    .name("Ana")
    .email("ana@example.com")
    .active(true)
);
```

Outra forma possível para operações mais complexas:

```java
UserCreate input = UserCreate.builder()
    .name("Ana")
    .email("ana@example.com")
    .build();

User user = db.user().create(input);
```

A API final deve privilegiar clareza e autocomplete.

---

# 18. Leitura por chave

```java
Optional<User> user = db.user().findUnique(q -> q
    .where(UserWhere.id().eq(userId))
);
```

Atalho gerado para chave primária:

```java
Optional<User> user = db.user().findById(userId);
```

---

# 19. Find many

```java
List<User> users = db.user().findMany();
```

Com filtro:

```java
List<User> users = db.user().findMany(q -> q
    .where(UserWhere.active().eq(true))
);
```

---

# 20. Filtros type-safe

## 20.1 String

```java
UserWhere.name().eq("Mamadu")
UserWhere.name().notEq("Mamadu")
UserWhere.name().contains("ama")
UserWhere.name().startsWith("Ma")
UserWhere.name().endsWith("du")
UserWhere.name().in(List.of("Ana", "João"))
UserWhere.name().isNull()
```

Operações incompatíveis não devem existir na API gerada.

Por exemplo, um UUID não deve expor `contains()`.

---

## 20.2 Valores numéricos

```java
ProductWhere.price().gt(new BigDecimal("100"))
ProductWhere.price().gte(new BigDecimal("100"))
ProductWhere.price().lt(new BigDecimal("500"))
ProductWhere.price().between(
    new BigDecimal("100"),
    new BigDecimal("500")
)
```

---

## 20.3 Datas

```java
OrderWhere.createdAt().after(from)
OrderWhere.createdAt().before(to)
OrderWhere.createdAt().between(from, to)
```

---

## 20.4 Composição lógica

```java
UserCondition condition = UserWhere.active().eq(true)
    .and(
        UserWhere.email().endsWith("@example.com")
    );
```

Ou:

```java
UserCondition condition = UserWhere.anyOf(
    UserWhere.name().contains("ana"),
    UserWhere.email().contains("ana")
);
```

---

# 21. Ordenação

```java
List<User> users = db.user().findMany(q -> q
    .orderBy(UserOrder.createdAt().desc())
);
```

Ordenação múltipla:

```java
.orderBy(
    UserOrder.active().desc(),
    UserOrder.name().asc()
)
```

---

# 22. Paginação

## 22.1 Offset pagination

```java
List<User> users = db.user().findMany(q -> q
    .skip(0)
    .take(20)
);
```

---

## 22.2 Página estruturada

O Tuprel deverá fornecer um helper opcional:

```java
Page<User> page = db.user().paginate(q -> q
    .page(1)
    .size(20)
    .where(UserWhere.active().eq(true))
);
```

Resultado:

```java
page.items();
page.page();
page.size();
page.totalItems();
page.totalPages();
page.hasNext();
```

---

## 22.3 Cursor pagination

Fundamental para tabelas grandes:

```java
CursorPage<User> page = db.user().findManyCursor(q -> q
    .after(cursor)
    .take(50)
    .orderBy(UserOrder.id().asc())
);
```

O cursor deverá ser opaco por omissão.

---

# 23. Actualização

```java
User user = db.user().update(q -> q
    .where(UserWhere.id().eq(userId))
    .data(data -> data
        .name("Novo nome")
        .active(true)
    )
);
```

Actualização em massa:

```java
long updated = db.user().updateMany(q -> q
    .where(UserWhere.active().eq(false))
    .data(data -> data.archived(true))
);
```

Operações massivas deverão deixar claro que podem afectar vários registos.

---

# 24. Eliminação

```java
db.user().delete(q -> q
    .where(UserWhere.id().eq(userId))
);
```

Múltiplos:

```java
long deleted = db.session().deleteMany(q -> q
    .where(SessionWhere.expiresAt().before(Instant.now()))
);
```

Deverá existir protecção opcional contra `deleteMany` sem filtro.

---

# 25. Upsert

```java
User user = db.user().upsert(q -> q
    .where(UserWhere.email().eq(email))
    .create(data -> data
        .name(name)
        .email(email)
    )
    .update(data -> data
        .name(name)
    )
);
```

Sempre que possível, o dialecto deverá utilizar capacidades atómicas da base de dados.

---

# 26. Relações na API de queries

O Tuprel não deverá fazer lazy loading implícito.

Uma relação deve ser pedida de forma explícita:

```java
Optional<User> user = db.user().findUnique(q -> q
    .where(UserWhere.id().eq(userId))
    .include(UserInclude.products())
);
```

O objecto gerado pode representar relações através de um tipo com estado carregado/não carregado:

```java
user.orElseThrow().products().isLoaded();
user.orElseThrow().products().get();
```

Se a relação não foi pedida, chamar `get()` deverá produzir uma excepção clara:

```text
RelationNotLoadedException:
User.products was not loaded.
Add include(UserInclude.products()) to the query.
```

Isto evita queries escondidas.

---

# 27. Prevenção de N+1

O runtime deverá ter uma estratégia explícita para includes.

Possibilidades:

- JOIN quando apropriado;
- query principal + query batch para relações to-many;
- batching de foreign keys;
- limite configurável.

O Tuprel deverá evitar executar uma query por linha sem que o developer tenha pedido esse comportamento.

Em modo de desenvolvimento, poderá detectar padrões suspeitos de N+1 e produzir warnings.

---

# 28. Selecção de campos e projections

O developer deverá poder evitar carregar colunas desnecessárias.

A API final deverá manter type safety.

Uma abordagem proposta:

```java
List<UserSummary> users = db.user().findMany(
    UserProjection.of(
        UserFields.id(),
        UserFields.name(),
        UserFields.email(),
        UserSummary::new
    ),
    q -> q.where(UserWhere.active().eq(true))
);
```

Onde o developer define:

```java
public record UserSummary(
    UUID id,
    String name,
    String email
) {}
```

O generator conhece os tipos dos campos e pode validar a assinatura da projection.

A implementação exacta pode ser simplificada durante o desenvolvimento, mas partial selects devem fazer parte da versão estável.

---

# 29. Agregações

```java
long count = db.user().count(q -> q
    .where(UserWhere.active().eq(true))
);
```

```java
ProductAggregateResult stats = db.product().aggregate(q -> q
    .avg(ProductFields.price())
    .min(ProductFields.price())
    .max(ProductFields.price())
    .count()
);
```

---

# 30. Group by

```java
List<OrderGroupResult> result = db.order().groupBy(q -> q
    .by(OrderFields.status())
    .count()
    .sum(OrderFields.total())
);
```

A API deve continuar type-safe.

---

# 31. Operações batch

Para aplicações empresariais, batch não pode ser uma funcionalidade secundária.

```java
db.user().createMany(users);
```

```java
db.product().updateMany(...);
```

O runtime deverá usar batching JDBC ou estratégias PostgreSQL eficientes quando apropriado.

Deverão existir limites configuráveis para evitar payloads excessivos.

---

# 32. Streaming de resultados

Para grandes volumes:

```java
try (TuprelStream<User> users = db.user().stream(q -> q
    .where(UserWhere.active().eq(true))
    .fetchSize(500)
)) {
    users.forEach(...);
}
```

O contrato deverá garantir fecho de ResultSet, statement e recursos associados.

---

# 33. Transacções

## 33.1 API simples

```java
Order order = db.transaction(tx -> {
    Order createdOrder = tx.order().create(...);
    tx.stock().update(...);
    tx.payment().create(...);
    return createdOrder;
});
```

Uma excepção não tratada deverá provocar rollback.

---

## 33.2 Configuração

```java
db.transaction(
    TransactionOptions.builder()
        .isolation(IsolationLevel.SERIALIZABLE)
        .timeout(Duration.ofSeconds(10))
        .readOnly(false)
        .build(),
    tx -> {
        ...
    }
);
```

Níveis propostos:

- READ_COMMITTED
- REPEATABLE_READ
- SERIALIZABLE

Os níveis suportados dependem da base de dados.

---

## 33.3 Nested transactions

A primeira versão poderá suportar nested transaction através de savepoints.

O comportamento deve ser documentado e nunca simular uma semântica que a base de dados não suporta.

---

# 34. Integração com `@Transactional`

No módulo Spring Boot, o Tuprel deverá integrar-se com o sistema de transacções do Spring.

Exemplo desejado:

```java
@Service
public class CheckoutService {

    private final TuprelClient db;

    public CheckoutService(TuprelClient db) {
        this.db = db;
    }

    @Transactional
    public void checkout(...) {
        db.order().create(...);
        db.stock().update(...);
        db.payment().create(...);
    }
}
```

As três operações devem usar a mesma transacção.

Isto é um requisito importante para adopção por equipas Spring.

---

# 35. Optimistic locking

O schema poderá marcar um campo de versão:

```tuprel
model Product {
    id      UUID @id @default(uuid())
    stock   Int
    version Int  @version
}
```

O update deverá incluir a versão esperada.

Se outro processo alterou o registo, o Tuprel lança:

```text
OptimisticLockException
```

Isto é importante para concorrência em aplicações empresariais.

---

# 36. Soft delete opcional

Soft delete não deve ser comportamento implícito global.

Pode ser activado por modelo:

```tuprel
model User {
    id        UUID     @id @default(uuid())
    deletedAt Instant?

    @@softDelete(field: deletedAt)
}
```

Por omissão, queries excluem os registos eliminados logicamente.

A API deverá permitir:

```java
.withDeleted()
.onlyDeleted()
.restore(...)
```

A documentação deverá destacar este comportamento claramente.

---

# 37. Campos automáticos

```tuprel
createdAt Instant @default(now())
updatedAt Instant @updatedAt
```

O Tuprel deverá definir claramente se o valor é produzido pela base de dados ou pelo runtime.

Preferência: valores que afectam integridade devem ser gerados pela base de dados sempre que possível.

---

# 38. SQL raw

Nenhum ORM cobre todos os casos.

O Tuprel deverá oferecer uma escape hatch segura.

```java
List<SqlRow> rows = db.sql().query(
    "SELECT id, name FROM users WHERE created_at >= ?",
    from
);
```

Para updates:

```java
int affected = db.sql().execute(
    "UPDATE users SET active = false WHERE last_login < ?",
    threshold
);
```

Parâmetros nunca devem ser interpolados automaticamente em strings SQL.

Prepared statements devem ser o comportamento normal.

---

# 39. Queries SQL tipadas

Uma funcionalidade posterior à base do 1.0, mas altamente interessante, é permitir ficheiros SQL compilados.

```text
src/main/tuprel-sql/find_active_users.sql
```

```sql
SELECT id, name, email
FROM users
WHERE active = true
  AND created_at >= :from
ORDER BY created_at DESC;
```

Comando:

```bash
tuprel sql generate
```

Resultado conceptual:

```java
List<FindActiveUsersRow> result = db.queries()
    .findActiveUsers(from);
```

Esta funcionalidade permite usar SQL directamente sem perder geração de tipos.

Pode ser entregue depois do core estável se o calendário do 1.0 ficar demasiado grande.

---

# 40. Preview de SQL

Uma query deverá poder ser inspeccionada sem ser executada.

```java
UserQuery query = db.user().query()
    .where(UserWhere.active().eq(true))
    .orderBy(UserOrder.createdAt().desc());

SqlPreview preview = query.toSql();
```

Resultado:

```text
SELECT ... FROM users WHERE active = $1 ORDER BY created_at DESC
Parameters: [true]
```

Em produção, valores sensíveis devem poder ser mascarados.

---

# 41. Explain e análise de queries

O Tuprel deverá facilitar performance tuning.

```java
ExplainPlan plan = query.explain();
```

Opcionalmente:

```java
ExplainPlan plan = query.explainAnalyze();
```

`EXPLAIN ANALYZE` executa a query e deverá exigir uma chamada explícita.

O Studio poderá apresentar o plano visualmente.

---

# 42. Migrações

A pasta:

```text
tuprel/migrations/
```

Exemplo:

```text
tuprel/migrations/
├── 20260919_203421_init/
│   └── migration.sql
├── 20260920_101502_add_user_status/
│   └── migration.sql
└── 20260922_173011_add_orders/
    └── migration.sql
```

---

# 43. Tabela interna de migrações

O Tuprel deverá manter uma tabela interna, por exemplo:

```text
_tuprel_migrations
```

Campos mínimos:

- id;
- migration_name;
- checksum;
- started_at;
- finished_at;
- execution_time;
- status;
- error;
- applied_by ou deployment metadata opcional.

---

# 44. `tuprel migrate dev`

```bash
tuprel migrate dev --name add-products
```

Fluxo:

```text
ler schema
    ↓
validar schema
    ↓
obter estado da base de dados de desenvolvimento
    ↓
calcular diff
    ↓
detectar alterações destrutivas
    ↓
gerar migration.sql
    ↓
aplicar migração
    ↓
actualizar histórico
    ↓
regenerar client
```

Saída:

```text
Tuprel ORM

✓ Schema valid
✓ Database connection established
✓ 3 schema changes detected
✓ Migration created: 20260919_203421_add_products
✓ Migration applied
✓ Client generated

Done in 842 ms
```

---

# 45. Alterações destrutivas

O migration engine deverá detectar, pelo menos:

- eliminar coluna;
- reduzir comprimento de coluna;
- alterar tipo com possível perda;
- tornar nullable em not-null quando existem nulos;
- remover tabela;
- remover enum value;
- alterar foreign key destrutivamente;
- recriar índice ou constraint crítica.

Em desenvolvimento:

```text
WARNING: This migration may cause data loss.
- Column users.legacy_code will be dropped.
```

Para comandos perigosos deverá ser necessário consentimento explícito quando executados de forma interactiva.

Em CI, flags explícitas devem controlar o comportamento.

---

# 46. `tuprel migrate create`

Cria a migração mas não a aplica:

```bash
tuprel migrate create --name add-orders
```

Útil quando o developer quer rever ou editar o SQL primeiro.

---

# 47. `tuprel migrate deploy`

Comando para produção:

```bash
tuprel migrate deploy
```

Deverá:

- aplicar apenas migrações ainda não aplicadas;
- respeitar ordem;
- validar checksums;
- adquirir lock de migração;
- falhar de forma segura;
- não gerar nova migração;
- não fazer reset;
- produzir exit code adequado a CI/CD.

Produção deve seguir uma filosofia forward-only.

Rollback automático de alterações complexas não deve ser prometido.

---

# 48. Migration locking

Dois deployments não devem aplicar a mesma migração em simultâneo.

No PostgreSQL, o engine pode utilizar advisory locks ou mecanismo equivalente.

---

# 49. Checksum de migrações

Depois de uma migração ter sido aplicada, alterações manuais ao ficheiro devem ser detectadas.

```text
Migration checksum mismatch:
20260919_203421_init
```

Isto protege ambientes partilhados.

---

# 50. Drift detection

O Tuprel deverá conseguir detectar quando a estrutura real da base de dados já não corresponde ao histórico de migrações.

Comando:

```bash
tuprel migrate status
```

Saída possível:

```text
Database: PostgreSQL
Migrations applied: 18
Pending migrations: 2
Schema drift: detected

Drift:
- index users_email_idx exists in database but not in migration history
```

---

# 51. `tuprel migrate diff`

```bash
tuprel migrate diff
```

Pode comparar:

- schema Tuprel vs base de dados;
- duas bases de dados;
- migration history vs base actual;
- dois schemas.

Saída opcional:

```bash
tuprel migrate diff --script
```

para imprimir SQL.

---

# 52. `tuprel migrate resolve`

Para recuperação operacional:

```bash
tuprel migrate resolve --applied 20260919_...
```

ou:

```bash
tuprel migrate resolve --failed 20260919_...
```

O comando deve ser avançado, documentado e protegido contra utilização acidental.

---

# 53. `tuprel migrate reset`

Somente desenvolvimento e testes.

```bash
tuprel migrate reset
```

Deverá exigir confirmação forte e recusar por omissão ambientes classificados como production.

Fluxo:

```text
drop/recreate schema
      ↓
aplicar todas as migrações
      ↓
executar seed opcional
      ↓
gerar client
```

---

# 54. `tuprel db push`

Sincronização rápida para prototipagem:

```bash
tuprel db push
```

Não cria histórico de migração normal.

Deve ser recomendado para:

- protótipos;
- testes locais;
- experiências.

Não deve ser recomendado como estratégia principal de produção.

Alterações destrutivas exigem:

```bash
tuprel db push --accept-data-loss
```

---

# 55. Introspecção com `tuprel db pull`

Para uma base existente:

```bash
tuprel db pull
```

O engine deverá ler:

- tabelas;
- colunas;
- tipos;
- nulabilidade;
- primary keys;
- foreign keys;
- unique constraints;
- índices;
- enums;
- defaults;
- sequences relevantes;
- comments quando possível.

Depois actualiza ou cria:

```text
tuprel/schema.tuprel
```

O comando deve preservar formatting e comentários do schema sempre que tecnicamente possível.

---

# 56. Seed

O seed deve ser Java-native.

Exemplo:

```java
@TuprelSeed
public final class DatabaseSeed {

    public void run(TuprelClient db) {
        db.user().create(data -> data
            .name("Administrator")
            .email("admin@example.com")
        );
    }
}
```

Configuração:

```toml
[seed]
class = "com.example.app.database.DatabaseSeed"
```

Execução:

```bash
tuprel db seed
```

O seed deve ser opcional.

---

# 57. Tuprel Studio

Comando:

```bash
tuprel studio
```

Por omissão:

```text
http://localhost:5555
```

O Studio deverá abrir apenas em `localhost` por segurança, excepto se o developer configurar explicitamente outra interface.

---

# 58. Funcionalidades do Studio 1.0

O Studio deverá permitir:

- listar modelos;
- visualizar registos;
- pesquisar;
- filtrar;
- ordenar;
- paginar;
- criar registo;
- editar registo;
- eliminar registo com confirmação;
- navegar relações;
- visualizar schema;
- visualizar índices;
- visualizar constraints;
- ver histórico de migrações;
- copiar identificadores;
- visualizar JSON de um registo;
- abrir SQL gerado para uma operação;
- exportar um conjunto pequeno de dados para JSON/CSV;
- modo read-only.

Funcionalidades futuras:

- visualização de explain plans;
- query console;
- dashboard de slow queries;
- diagramas ER interactivos.

---

# 59. Segurança do Studio

Requisitos:

- bind em `127.0.0.1` por omissão;
- aviso forte se exposto externamente;
- credenciais nunca mostradas no browser;
- sessões locais curtas;
- CSRF protection para operações de escrita;
- opção `--read-only`;
- confirmação para delete/update massivo;
- headers de segurança adequados.

---

# 60. Integração com Spring Boot

Dependência proposta:

```gradle
implementation("dev.tuprel:tuprel-spring-boot-starter:<version>")
```

O starter deverá:

- detectar o `DataSource`;
- criar `TuprelClient` como bean;
- reutilizar o connection pool configurado pelo Spring;
- participar em `@Transactional`;
- integrar logging;
- disponibilizar health indicator opcional;
- disponibilizar métricas opcionais via Actuator/Micrometer;
- traduzir excepções quando configurado.

Exemplo:

```java
@Service
public final class UserService {

    private final TuprelClient db;

    public UserService(TuprelClient db) {
        this.db = db;
    }

    public List<User> listActiveUsers() {
        return db.user().findMany(q -> q
            .where(UserWhere.active().eq(true))
        );
    }
}
```

---

# 61. Java sem Spring

O core deve funcionar sem framework.

```java
try (TuprelClient db = TuprelClient.builder()
    .url(System.getenv("DATABASE_URL"))
    .build()) {

    List<User> users = db.user().findMany();
}
```

A API exacta de bootstrap dependerá do módulo de datasource escolhido.

---

# 62. Connection pool

O Tuprel não deverá obrigar uma implementação específica quando a aplicação já fornece `DataSource`.

Estratégia:

- Spring Boot: reutilizar `DataSource` existente;
- Java standalone: módulo opcional com pool recomendado;
- permitir qualquer `javax.sql.DataSource` compatível.

O core não deve criar silenciosamente múltiplos pools para a mesma aplicação.

---

# 63. Virtual threads

O runtime deverá ser desenhado para funcionar correctamente com Java virtual threads.

Requisitos:

- não depender de thread locals globais desnecessários;
- não manter locks Java durante I/O de base de dados sem necessidade;
- garantir que contexto transaccional é correctamente propagado na integração escolhida;
- documentar limites do driver JDBC.

---

# 64. Gradle Plugin

Plugin proposto:

```kotlin
plugins {
    id("dev.tuprel") version "<version>"
}
```

Tasks:

```bash
./gradlew tuprelValidate
./gradlew tuprelGenerate
./gradlew tuprelMigrateDev
./gradlew tuprelMigrateDeploy
./gradlew tuprelFormat
```

O plugin deverá registar automaticamente generated sources.

`compileJava` deve depender de `tuprelGenerate` quando configurado.

---

# 65. Maven Plugin

Goals propostos:

```bash
./mvnw tuprel:validate
./mvnw tuprel:generate
./mvnw tuprel:migrate-dev
./mvnw tuprel:migrate-deploy
./mvnw tuprel:format
```

O plugin deverá adicionar `target/generated-sources/tuprel` ao compile source path.

---

# 66. CLI completa

A CLI deverá ser consistente, scriptable e adequada a CI.

## Inicialização

```bash
tuprel init
```

## Diagnóstico

```bash
tuprel doctor
```

## Schema

```bash
tuprel validate
tuprel format
tuprel schema print
tuprel schema graph
```

## Code generation

```bash
tuprel generate
tuprel generate --check
tuprel generate --watch
```

## Base de dados

```bash
tuprel db pull
tuprel db push
tuprel db seed
tuprel db execute --file script.sql
```

## Migrações

```bash
tuprel migrate dev --name init
tuprel migrate create --name add-users
tuprel migrate deploy
tuprel migrate status
tuprel migrate diff
tuprel migrate resolve ...
tuprel migrate reset
```

## Studio

```bash
tuprel studio
tuprel studio --read-only
```

## Informação

```bash
tuprel version
tuprel info
tuprel completions bash
tuprel completions zsh
tuprel completions fish
```

---

# 67. `tuprel doctor`

Este comando é importante para reduzir problemas de setup.

```bash
tuprel doctor
```

Exemplo de saída:

```text
Tuprel Doctor

✓ Java 21 detected
✓ Gradle project detected
✓ PostgreSQL driver detected
✓ schema.tuprel found
✓ schema valid
✓ DATABASE_URL defined
✓ database connection successful
✓ generated sources configured
✓ migrations directory healthy

No problems found.
```

Problemas devem incluir solução:

```text
✗ DATABASE_URL is not defined
  Set DATABASE_URL or configure database.urlEnv in tuprel.toml.
```

---

# 68. Mensagens de erro

Erros devem ser tratados como parte do produto.

Mau erro:

```text
NullPointerException at SqlBuilder.java:183
```

Erro esperado:

```text
TUPREL-SCHEMA-104
Invalid relation Product.seller

Field sellerId is UUID?, but User.id is UUID.
The relation requires compatible nullability.

tuprel/schema.tuprel:42:5

40 | seller   User @relation(fields: [sellerId], references: [id])
41 | sellerId UUID?
42 |

Suggestion:
Make seller optional or change sellerId to UUID.
```

Todos os erros importantes deverão ter:

- código estável;
- descrição;
- localização;
- causa provável;
- sugestão;
- link futuro para documentação.

---

# 69. Arquitectura de alto nível

```text
                        ┌─────────────────────┐
                        │   schema.tuprel     │
                        └──────────┬──────────┘
                                   │
                                   ▼
                        ┌─────────────────────┐
                        │   Schema Parser     │
                        └──────────┬──────────┘
                                   │
                                   ▼
                        ┌─────────────────────┐
                        │       AST           │
                        └──────────┬──────────┘
                                   │
                                   ▼
                        ┌─────────────────────┐
                        │ Schema Validator    │
                        └──────┬───────┬──────┘
                               │       │
                 ┌─────────────┘       └─────────────┐
                 ▼                                   ▼
       ┌──────────────────┐                ┌──────────────────┐
       │   Code Generator │                │ Migration Engine │
       └────────┬─────────┘                └────────┬─────────┘
                │                                   │
                ▼                                   ▼
       Generated Java                         PostgreSQL schema
                │
                ▼
       ┌──────────────────┐
       │  Tuprel Runtime  │
       └────────┬─────────┘
                │
                ▼
       ┌──────────────────┐
       │   Query AST      │
       └────────┬─────────┘
                │
                ▼
       ┌──────────────────┐
       │ PostgreSQL Dialect│
       └────────┬─────────┘
                │
                ▼
       ┌──────────────────┐
       │ PreparedStatement│
       └────────┬─────────┘
                │
                ▼
           PostgreSQL
```

---

# 70. Estrutura interna do repositório Tuprel

Proposta de monorepo:

```text
tuprel/
├── tuprel-schema-ast/
├── tuprel-schema-parser/
├── tuprel-schema-validator/
├── tuprel-schema-formatter/
│
├── tuprel-codegen/
├── tuprel-codegen-java/
│
├── tuprel-sql-ast/
├── tuprel-query-engine/
├── tuprel-runtime/
├── tuprel-jdbc/
│
├── tuprel-dialect-api/
├── tuprel-postgresql/
│
├── tuprel-migrations/
├── tuprel-introspection/
│
├── tuprel-cli/
├── tuprel-gradle-plugin/
├── tuprel-maven-plugin/
│
├── tuprel-spring/
├── tuprel-spring-boot-starter/
│
├── tuprel-testing/
│
├── tuprel-studio-server/
├── tuprel-studio-web/
│
├── integration-tests/
├── benchmarks/
├── examples/
└── docs/
```

---

# 71. `tuprel-schema-ast`

Responsabilidades:

- representar modelos;
- fields;
- tipos;
- enums;
- relações;
- índices;
- constraints;
- datasource;
- generator configuration;
- source locations.

Este módulo não deve conhecer JDBC.

---

# 72. `tuprel-schema-parser`

Responsabilidades:

- lexer;
- parser;
- mensagens de syntax error;
- comments;
- source spans;
- recovery de parsing para conseguir mostrar vários erros de uma vez.

O parser deverá produzir AST, não código Java directamente.

---

# 73. `tuprel-schema-validator`

Validações:

- nomes duplicados;
- tipos inexistentes;
- relation target inexistente;
- foreign key incompatível;
- chave primária em falta, quando obrigatória;
- unique inválido;
- index inválido;
- default incompatível;
- ciclos problemáticos de cascade;
- atributos mutuamente incompatíveis;
- map duplicado;
- enums inválidos;
- campos reserved words;
- constraints incoerentes.

---

# 74. `tuprel-codegen`

O generator deverá usar um intermediate representation validado.

Não deve fazer parsing novamente.

Pipeline:

```text
AST
 ↓
Validated Schema Model
 ↓
Generator IR
 ↓
Java Source Model
 ↓
Formatter
 ↓
.java files
```

---

# 75. `tuprel-query-engine`

Responsabilidades:

- receber filtros type-safe;
- construir Query AST;
- validar operações runtime que não podem ser compiladas;
- gerar plano lógico;
- delegar SQL ao dialecto;
- bind de parâmetros;
- mapear resultados.

---

# 76. SQL AST

Não construir SQL através de concatenação arbitrária espalhada pelo código.

Representar queries como estrutura:

```text
SelectQuery
├── table
├── projections
├── joins
├── predicate
├── orderBy
├── limit
└── offset
```

Depois:

```text
Query AST
   ↓
PostgreSQL Renderer
   ↓
SQL + Parameters
```

Isto facilita:

- segurança;
- testes;
- múltiplos dialectos no futuro;
- logging;
- SQL preview;
- optimizações.

---

# 77. PostgreSQL dialect

Responsabilidades:

- quoting;
- tipos nativos;
- placeholders;
- RETURNING;
- UPSERT;
- sequences;
- JSONB;
- arrays quando suportados;
- enum types;
- locking;
- advisory lock para migrações;
- introspection queries;
- DDL;
- index syntax;
- transaction behaviour específico.

---

# 78. Mapeamento de resultados

O código gerado deverá produzir mappers específicos.

Exemplo conceptual:

```java
final class UserRowMapper {
    static User map(ResultSet rs) throws SQLException {
        return new User(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("email"),
            rs.getBoolean("active"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }
}
```

Evitar reflection para descobrir fields em cada linha.

---

# 79. Prepared statements

Queries parametrizadas são obrigatórias no caminho normal.

Nunca:

```java
"SELECT * FROM users WHERE email = '" + email + "'"
```

Sempre produzir:

```sql
SELECT * FROM users WHERE email = ?
```

com bind de parâmetro separado.

---

# 80. Segurança contra SQL injection

Requisitos:

- parâmetros separados de SQL;
- identifiers gerados a partir de metadata validado;
- raw SQL claramente separado;
- APIs raw com parâmetros;
- warnings para APIs unsafe, caso existam;
- não permitir interpolação de identifier arbitrário sem escaping controlado.

---

# 81. Excepções

Hierarquia proposta:

```text
TuprelException
├── TuprelConfigurationException
├── TuprelSchemaException
├── TuprelConnectionException
├── TuprelQueryException
│   ├── UniqueConstraintViolationException
│   ├── ForeignKeyViolationException
│   ├── NotNullViolationException
│   ├── RecordNotFoundException
│   ├── OptimisticLockException
│   └── RelationNotLoadedException
├── TuprelTransactionException
├── TuprelMigrationException
└── TuprelIntrospectionException
```

A excepção deverá preservar:

- SQLState;
- vendor error code;
- constraint name;
- table/model quando identificável;
- operação;
- causa original.

Segredos e parâmetros sensíveis não devem aparecer por omissão.

---

# 82. Observabilidade

O Tuprel 1.0 deverá ter observabilidade desde o início.

## Logs

Eventos:

- query iniciada;
- query concluída;
- query lenta;
- erro SQL;
- transacção begin/commit/rollback;
- migration;
- connection diagnostics.

O SQL logging deverá ser configurável.

---

# 83. Slow query detection

Configuração:

```toml
[logging]
slowQueryThresholdMs = 500
```

Log:

```text
TUPREL SLOW QUERY 812 ms
Model: Order
Operation: findMany
SQL: SELECT ...
```

Parâmetros sensíveis devem ser mascarados.

---

# 84. Métricas

Métricas propostas:

- query count;
- query duration;
- slow queries;
- failures;
- transaction count;
- rollback count;
- rows returned;
- batch sizes;
- migration duration.

Integração Spring pode expor via Micrometer.

Nomes de labels devem evitar cardinalidade elevada.

Nunca usar SQL completo como label de métrica.

---

# 85. OpenTelemetry

Uma integração opcional poderá gerar spans:

```text
tuprel.user.findMany
tuprel.order.create
tuprel.transaction
```

A instrumentação deve seguir convenções de database spans e não vazar valores sensíveis.

---

# 86. Performance

Objectivos de arquitectura:

- sem reflection no hot path normal;
- prepared statements;
- mappers gerados;
- alocações controladas;
- batching;
- fetch size configurável;
- query AST eficiente;
- SQL rendering cacheável quando possível;
- evitar construção repetida de metadata.

O repositório deverá conter benchmarks reproduzíveis.

Não devem ser feitas promessas públicas de performance sem benchmarks verificáveis.

---

# 87. Benchmark suite

Criar:

```text
benchmarks/
├── simple-select/
├── select-1000-rows/
├── insert-single/
├── insert-batch/
├── update/
├── relation-fetch/
├── transaction/
└── startup/
```

Comparar internamente com JDBC directo para medir overhead do próprio Tuprel.

O objectivo é encontrar regressões, não produzir marketing enganador.

---

# 88. Thread safety

`TuprelClient` deverá ser thread-safe quando partilhado como singleton.

Delegates gerados também devem ser thread-safe.

Objetos de transaction são restritos ao contexto da transacção e não devem ser utilizados depois de concluída.

---

# 89. Imutabilidade dos modelos

Preferência para modelos de leitura imutáveis.

Java records podem ser utilizados quando adequado.

Exemplo:

```java
public record User(
    UUID id,
    String name,
    String email,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {}
```

Relações e estados especiais podem exigir generated classes em vez de records puros.

A decisão final deverá privilegiar API clara e compatibilidade futura.

---

# 90. Nullability

O generator deverá distinguir:

```tuprel
name String
bio  String?
```

É recomendado emitir annotations de nullability compatíveis com ferramentas modernas, sem obrigar toda a aplicação a adoptar uma biblioteca específica.

O design pode utilizar JSpecify ou uma abstraction própria mínima, desde que não crie lock-in desnecessário.

---

# 91. Validação de input

O Tuprel é responsável por constraints de persistência, não por todas as regras de negócio.

Exemplo:

- `NOT NULL`: Tuprel pode validar;
- comprimento máximo: Tuprel pode validar opcionalmente e a base valida definitivamente;
- `UNIQUE`: base de dados é autoridade final;
- idade mínima de cliente: regra de domínio, não do ORM.

Spring Validation/Jakarta Validation podem coexistir normalmente.

---

# 92. Mapeamento de erros de constraint

Em vez de o developer receber apenas uma SQLException genérica:

```java
try {
    db.user().create(...);
} catch (UniqueConstraintViolationException ex) {
    ex.constraint();
    ex.model();
}
```

Isto simplifica tratamento de erros de API.

---

# 93. Testing toolkit

Módulo:

```text
tuprel-testing
```

Deverá facilitar testes reais contra PostgreSQL.

Com Testcontainers:

```java
@TuprelTest
class UserRepositoryTest {

    @InjectTuprel
    TuprelClient db;

    @Test
    void createsUser() {
        ...
    }
}
```

A integração exacta pode variar entre JUnit e Spring.

---

# 94. Estratégia de testes recomendada

O ORM deve incentivar testes com base de dados real quando o comportamento SQL importa.

Tipos:

- unit tests para lógica sem DB;
- integration tests com PostgreSQL;
- migration tests;
- schema parser tests;
- codegen golden tests;
- JDBC mapping tests;
- concurrency tests;
- stress tests;
- compatibility tests.

---

# 95. Golden tests para geração de código

Entrada:

```text
fixtures/schema/user.tuprel
```

Saída esperada:

```text
fixtures/expected/User.java
fixtures/expected/UserWhere.java
...
```

Mudanças no generator tornam-se visíveis em code review.

---

# 96. Testes de migrações

Casos mínimos:

- create table;
- add column;
- rename;
- type change;
- nullable para required;
- unique;
- index;
- foreign key;
- enum change;
- composite key;
- cascade;
- failure a meio da migration;
- checksum mismatch;
- concurrent deploy;
- drift.

---

# 97. CI/CD

Pipeline recomendado:

```bash
tuprel validate
tuprel generate --check
./gradlew test
```

No deployment:

```bash
tuprel migrate deploy
java -jar app.jar
```

Em Kubernetes, a migration deve ser executada de forma controlada, por exemplo num job de deployment, e não necessariamente por todas as réplicas da aplicação.

---

# 98. Compatibilidade de migrações em zero-downtime

A documentação deve ensinar migrations expand/contract.

Exemplo:

1. adicionar nova coluna nullable;
2. deploy da aplicação que escreve nas duas colunas;
3. backfill;
4. tornar nova coluna obrigatória;
5. remover utilização antiga;
6. eliminar coluna antiga numa release posterior.

O ORM não consegue tornar automaticamente toda migração zero-downtime.

Mas pode detectar padrões perigosos e avisar.

---

# 99. Schema format

```bash
tuprel format
```

Deverá produzir formatação determinística.

Exemplo:

```tuprel
model User {
    id    UUID   @id @default(uuid())
    name  String
    email String @unique
}
```

Não deverá reordenar models ou fields de forma surpreendente sem configuração explícita.

---

# 100. Schema validation

```bash
tuprel validate
```

Deve funcionar sem ligação à base de dados quando só estiver a validar sintaxe e semântica local.

Com flag opcional:

```bash
tuprel validate --database
```

pode também verificar compatibilidade com a base real.

---

# 101. Schema modular

Projectos grandes não devem ser obrigados a manter tudo num único ficheiro.

Estrutura:

```text
tuprel/
├── schema.tuprel
├── models/
│   ├── auth.tuprel
│   ├── users.tuprel
│   ├── products.tuprel
│   ├── orders.tuprel
│   └── payments.tuprel
└── migrations/
```

`schema.tuprel`:

```tuprel
import "./models/auth.tuprel"
import "./models/users.tuprel"
import "./models/products.tuprel"
import "./models/orders.tuprel"
import "./models/payments.tuprel"
```

Imports devem ser resolvidos de forma determinística e ciclos devem ser detectados.

---

# 102. Naming conventions

Configuração global:

```toml
[naming]
tables = "snake_case"
columns = "snake_case"
```

O schema mantém Java-friendly names.

Exemplo:

```tuprel
createdAt Instant
```

Base:

```text
created_at
```

O developer deve poder sobrescrever com `@map`.

---

# 103. Multi-schema PostgreSQL

O Tuprel 1.0 deverá considerar suporte a schemas PostgreSQL:

```tuprel
model AuditLog {
    ...
    @@schema("audit")
}
```

Ou configuração por namespace.

Esta funcionalidade é importante em aplicações empresariais.

---

# 104. JSONB

PostgreSQL utiliza JSONB em muitos projectos modernos.

O Tuprel deverá oferecer:

```tuprel
metadata Json?
```

Operações básicas:

```java
EventWhere.metadata().contains(...)
EventWhere.metadata().path("source").eq("mobile")
```

A API avançada pode evoluir, mas leitura/escrita JSONB deve existir em 1.0.

---

# 105. Arrays PostgreSQL

Suporte recomendado:

```tuprel
tags String[]
```

Filtros:

```java
PostWhere.tags().contains("java")
PostWhere.tags().containsAll(List.of("java", "backend"))
```

Esta funcionalidade pode ser marcada PostgreSQL-specific.

---

# 106. Full-text search

Não precisa de ser uma abstraction universal no 1.0.

O PostgreSQL module pode oferecer uma extensão opcional posteriormente.

Até lá, raw SQL e typed SQL queries cobrem casos avançados.

---

# 107. Locks de linha

API para concorrência:

```java
Optional<Order> order = tx.order().findUnique(q -> q
    .where(OrderWhere.id().eq(orderId))
    .forUpdate()
);
```

Opções PostgreSQL relevantes podem incluir:

- FOR UPDATE;
- FOR NO KEY UPDATE;
- SKIP LOCKED;
- NOWAIT.

Devem ser expostas de forma explícita, não através de flags obscuras.

---

# 108. Query timeout

```java
List<User> users = db.user().findMany(q -> q
    .timeout(Duration.ofSeconds(2))
);
```

Pode também existir timeout global.

---

# 109. Cancellation

Quando tecnicamente suportado pelo driver, o Tuprel deverá permitir cancelamento de operação longa.

Isto é particularmente útil para requests cancelados e jobs.

---

# 110. Retries

O ORM não deve repetir writes automaticamente sem compreender idempotência.

Retries transaccionais podem existir como funcionalidade opt-in:

```java
db.transaction(
    TransactionOptions.serializable()
        .retryOnSerializationFailure(3),
    tx -> ...
);
```

Só erros seguros e documentados devem ser elegíveis.

---

# 111. Auditabilidade

O Tuprel deverá conseguir emitir eventos de lifecycle sem obrigar o developer a interceptar JDBC.

Possível SPI:

```java
interface TuprelQueryListener {
    void before(QueryEvent event);
    void after(QueryResultEvent event);
    void error(QueryErrorEvent event);
}
```

Usos:

- tracing;
- métricas;
- logs;
- profiling;
- auditoria técnica.

Não deve ser usado para esconder regras de negócio importantes.

---

# 112. Lifecycle hooks

Hooks de modelo devem ser cuidadosamente limitados.

Possível:

```text
beforeCreate
afterCreate
beforeUpdate
afterUpdate
beforeDelete
afterDelete
```

No 1.0, podem ficar de fora do core para evitar comportamento implícito.

A prioridade é previsibilidade.

Se forem introduzidos, devem ser opt-in e muito visíveis.

---

# 113. Cache

O Tuprel 1.0 não deve ter second-level cache automático.

Motivos:

- invalidação é complexa;
- comportamento pode ficar imprevisível;
- cria bugs difíceis de diagnosticar;
- Redis ou caches de domínio já podem ser usados pela aplicação.

Cache de metadata e planos internos é aceitável.

Cache de dados de negócio deve ficar fora do core inicialmente.

---

# 114. Reactive API

Não deverá ser requisito do Tuprel 1.0.

A prioridade é construir um runtime JDBC síncrono excelente, compatível com virtual threads.

Uma API R2DBC pode surgir numa versão posterior se houver procura real e capacidade de manutenção.

---

# 115. GraalVM Native Image

Como o design privilegia code generation e pouco reflection, o Tuprel deve ser arquitectado para compatibilidade com native image.

Não é necessário prometer suporte completo antes de existir uma suíte de testes real.

Quando suportado, deverá existir um exemplo oficial.

---

# 116. Requisitos funcionais

## RF-001: Inicialização

O sistema deve criar a estrutura base com `tuprel init`.

## RF-002: Detecção de projecto

Deve detectar Maven ou Gradle.

## RF-003: Schema parser

Deve interpretar ficheiros `.tuprel`.

## RF-004: Schema validation

Deve validar sintaxe e semântica.

## RF-005: Schema formatting

Deve formatar schema de forma determinística.

## RF-006: Models

Deve suportar definição de modelos.

## RF-007: Tipos

Deve suportar os tipos escalares definidos neste documento.

## RF-008: Nullability

Deve distinguir campos obrigatórios e opcionais.

## RF-009: Defaults

Deve suportar defaults comuns.

## RF-010: Primary keys

Deve suportar chaves simples e compostas.

## RF-011: Unique constraints

Deve suportar unique simples e composto.

## RF-012: Indexes

Deve suportar índices básicos e compostos.

## RF-013: Relations

Deve suportar 1:1, 1:N e N:N.

## RF-014: Referential actions

Deve suportar acções referenciais suportadas pelo PostgreSQL.

## RF-015: Enums

Deve suportar enum no schema e Java.

## RF-016: Code generation

Deve gerar cliente Java type-safe.

## RF-017: Deterministic generation

A mesma entrada deve produzir a mesma saída.

## RF-018: CRUD

Deve suportar create, read, update e delete.

## RF-019: Find unique

Deve consultar por chave única.

## RF-020: Find many

Deve consultar múltiplos registos.

## RF-021: Filters

Deve gerar filtros adequados a cada tipo.

## RF-022: Logical filters

Deve suportar AND, OR e NOT.

## RF-023: Ordering

Deve suportar ordenação simples e múltipla.

## RF-024: Offset pagination

Deve suportar skip/take.

## RF-025: Cursor pagination

Deve suportar paginação por cursor.

## RF-026: Partial selection

Deve permitir seleccionar colunas específicas de forma type-safe.

## RF-027: Includes

Deve permitir carregar relações explicitamente.

## RF-028: No implicit lazy loading

Não deve executar query automaticamente ao aceder a uma relação não carregada.

## RF-029: Upsert

Deve suportar upsert atómico quando disponível.

## RF-030: Batch create

Deve suportar criação em lote.

## RF-031: Batch update/delete

Deve suportar operações massivas controladas.

## RF-032: Aggregates

Deve suportar count, min, max, avg e sum onde aplicável.

## RF-033: Group by

Deve suportar agregações agrupadas.

## RF-034: Transactions

Deve suportar transacções programáticas.

## RF-035: Isolation level

Deve permitir configurar isolamento.

## RF-036: Savepoints

Deve suportar savepoints quando o dialecto permitir.

## RF-037: Spring transactions

A integração Spring deve participar em `@Transactional`.

## RF-038: Optimistic locking

Deve suportar campo de versão opt-in.

## RF-039: Row locking

Deve expor locking explícito suportado pelo PostgreSQL.

## RF-040: Raw SQL

Deve oferecer SQL parametrizado.

## RF-041: SQL preview

Deve permitir inspecção do SQL gerado.

## RF-042: Explain

Deve permitir executar EXPLAIN de forma explícita.

## RF-043: Migrations

Deve gerar e aplicar migrações.

## RF-044: Migration history

Deve manter histórico persistente.

## RF-045: Checksums

Deve detectar alteração em migração aplicada.

## RF-046: Migration locking

Deve impedir deploy concorrente inseguro.

## RF-047: Destructive change warnings

Deve identificar alterações potencialmente destrutivas.

## RF-048: Migration status

Deve mostrar estado das migrações.

## RF-049: Migration diff

Deve calcular diferenças de schema.

## RF-050: Drift detection

Deve detectar divergência da base real.

## RF-051: Database pull

Deve fazer introspecção PostgreSQL.

## RF-052: Database push

Deve sincronizar schema em desenvolvimento.

## RF-053: Seed

Deve executar seed Java configurado.

## RF-054: Studio

Deve permitir explorar e editar dados localmente.

## RF-055: Studio read-only

Deve ter modo apenas leitura.

## RF-056: Gradle plugin

Deve integrar geração no build Gradle.

## RF-057: Maven plugin

Deve integrar geração no build Maven.

## RF-058: Spring Boot starter

Deve disponibilizar auto-configuração.

## RF-059: External DataSource

Deve aceitar `DataSource` fornecido pela aplicação.

## RF-060: Standalone client

Deve funcionar sem framework.

## RF-061: Query logging

Deve permitir logging configurável.

## RF-062: Slow query logging

Deve detectar queries acima de threshold.

## RF-063: Metrics

Deve disponibilizar eventos/métricas integráveis.

## RF-064: Error mapping

Deve mapear constraints PostgreSQL para excepções Tuprel úteis.

## RF-065: CLI exit codes

Todos os comandos devem devolver códigos adequados para scripts e CI.

## RF-066: Doctor

Deve diagnosticar problemas comuns de instalação e configuração.

## RF-067: Modular schema

Deve permitir dividir schema em múltiplos ficheiros.

## RF-068: JSONB

Deve suportar leitura, escrita e filtros básicos.

## RF-069: PostgreSQL arrays

Deve suportar tipos array seleccionados.

## RF-070: Multi-schema PostgreSQL

Deve permitir mapear modelos para schemas PostgreSQL.

---

# 117. Requisitos não funcionais

## RNF-001: Segurança

Queries normais devem utilizar prepared statements.

## RNF-002: Determinismo

Code generation e formatting devem ser determinísticos.

## RNF-003: Thread safety

O client partilhado deve ser thread-safe.

## RNF-004: Performance

O runtime deve evitar reflection no hot path normal.

## RNF-005: Observabilidade

Operações importantes devem ser instrumentáveis.

## RNF-006: Compatibilidade

A primeira versão estável deverá estabelecer uma versão mínima de Java e uma matriz de compatibilidade publicada.

## RNF-007: Backwards compatibility

Versões 1.x devem seguir semantic versioning.

## RNF-008: Erros accionáveis

Mensagens devem explicar causa e possível correcção.

## RNF-009: IDE friendliness

Generated code deve fornecer autocomplete normal em IntelliJ e outras IDEs Java.

## RNF-010: Build integration

Generated code deve ser integrado automaticamente em Maven/Gradle.

## RNF-011: CI friendliness

Comandos devem funcionar sem prompts quando flags apropriadas são usadas.

## RNF-012: Secret safety

Credenciais e parâmetros sensíveis não devem aparecer por omissão em logs.

## RNF-013: Migration safety

Produção não deve executar reset ou data-loss operations implicitamente.

## RNF-014: Resiliência

Falhas de migration devem deixar estado diagnosticável.

## RNF-015: Testabilidade

Todos os módulos críticos devem ter integration tests.

## RNF-016: Documentação

Cada funcionalidade estável deve ter documentação e exemplos.

## RNF-017: Cross-platform CLI

A CLI deverá funcionar em Linux, macOS e Windows.

## RNF-018: Startup

A CLI deve ter arranque rápido suficiente para utilização frequente.

## RNF-019: Extensibilidade

Dialect e integrações devem ter limites arquitecturais claros.

## RNF-020: Zero hidden network access

O Tuprel não deve enviar telemetria sem consentimento explícito.

## RNF-021: Reproducibility

Builds e geração devem ser reproduzíveis.

## RNF-022: Accessibility

Tuprel Studio deverá seguir boas práticas de acessibilidade web.

## RNF-023: Resource safety

Statements, result sets e connections devem ser fechados correctamente mesmo em erro.

## RNF-024: Transaction correctness

Connections não podem escapar do seu contexto transaccional de forma insegura.

## RNF-025: Diagnostic stability

Códigos de erro públicos não devem mudar arbitrariamente.

---

# 118. Funcionalidades que realmente tornam o Tuprel interessante para developers Java

A lista seguinte representa o núcleo de valor do produto, não apenas extras.

## 118.1 Um schema legível

Um local central para compreender toda a estrutura de dados.

## 118.2 Client Java gerado e type-safe

Autocomplete real e menos strings espalhadas.

## 118.3 Sem necessidade de repositories repetitivos

O developer pode criar repositories de domínio quando fazem sentido, mas não deve ser obrigado a criar um ficheiro vazio por modelo.

## 118.4 Migrações integradas

A evolução da base de dados faz parte do mesmo workflow.

## 118.5 Introspection

Projectos existentes conseguem adoptar a ferramenta.

## 118.6 SQL visível

Nada impede o developer de perceber o que acontece.

## 118.7 Relações explícitas

Sem lazy loading inesperado.

## 118.8 `@Transactional` no Spring

Adopção natural em projectos reais.

## 118.9 Cursor pagination

Importante para produção e dados grandes.

## 118.10 Batch operations

Importante para performance.

## 118.11 Optimistic locking

Importante em cenários concorrentes.

## 118.12 PostgreSQL JSONB

Importante em aplicações modernas.

## 118.13 Explain/SQL preview

Ajuda developers a optimizar aplicações.

## 118.14 Studio

Reduz dependência de ferramentas externas para tarefas do dia-a-dia.

## 118.15 `tuprel doctor`

Transforma erros de configuração em problemas fáceis de resolver.

## 118.16 Erros de constraints compreensíveis

Reduz tempo de debugging.

## 118.17 Maven e Gradle de primeira classe

O Tuprel deve parecer natural no ecossistema Java.

## 118.18 Java standalone

Não fica preso a uma framework.

---

# 119. API de exemplo completa

Schema:

```tuprel
enum OrderStatus {
    PENDING
    PAID
    CANCELLED
}

model User {
    id        UUID      @id @default(uuid())
    name      String
    email     String    @unique
    orders    Order[]
    createdAt Instant   @default(now())
    updatedAt Instant   @updatedAt
}

model Order {
    id        UUID        @id @default(uuid())
    number    String      @unique
    status    OrderStatus @default(PENDING)
    total     Decimal
    userId    UUID
    user      User        @relation(fields: [userId], references: [id])
    createdAt Instant     @default(now())

    @@index([userId, createdAt])
}
```

Create:

```java
User user = db.user().create(data -> data
    .name("Mamadu")
    .email("mamadu@example.com")
);
```

Find:

```java
Optional<User> user = db.user().findUnique(q -> q
    .where(UserWhere.email().eq("mamadu@example.com"))
);
```

List:

```java
List<User> users = db.user().findMany(q -> q
    .where(UserWhere.createdAt().after(from))
    .orderBy(UserOrder.createdAt().desc())
    .take(50)
);
```

Include:

```java
User user = db.user().findUnique(q -> q
    .where(UserWhere.id().eq(userId))
    .include(UserInclude.orders())
).orElseThrow();
```

Transaction:

```java
Order order = db.transaction(tx -> {
    Order created = tx.order().create(data -> data
        .number(orderNumber)
        .userId(userId)
        .total(total)
    );

    tx.auditLog().create(data -> data
        .action("ORDER_CREATED")
        .entityId(created.id())
    );

    return created;
});
```

Aggregate:

```java
BigDecimal revenue = db.order().sum(
    OrderFields.total(),
    q -> q.where(OrderWhere.status().eq(OrderStatus.PAID))
);
```

---

# 120. Convenções para primeira versão

## Java mínimo

Proposta: Java 21.

Razões de produto:

- base moderna;
- records;
- virtual threads;
- APIs modernas;
- ciclo de vida longo em empresas.

Compatibilidade com versões Java posteriores deverá ser testada continuamente.

---

# 121. Primeira base de dados

PostgreSQL.

A primeira versão estável deve poder afirmar:

> Se a aplicação usa PostgreSQL, o Tuprel oferece uma experiência completa e suportada.

Só depois deverá expandir para outros dialectos.

---

# 122. Roadmap de suporte a bases de dados

Após a maturidade PostgreSQL:

1. SQLite, especialmente para desenvolvimento e aplicações pequenas;
2. MySQL/MariaDB;
3. SQL Server, se houver procura real;
4. outros dialectos conforme comunidade e capacidade de manutenção.

Cada dialecto precisa de uma suíte de conformidade própria.

---

# 123. O que deve estar pronto no Tuprel 1.0

A versão 1.0 deve ser considerada pronta para utilização real apenas quando incluir:

- schema language estável;
- parser;
- formatter;
- validator;
- generator Java;
- client type-safe;
- CRUD completo;
- filtros;
- relações;
- includes explícitos;
- select/projections;
- ordenação;
- offset pagination;
- cursor pagination;
- batch operations;
- aggregates;
- group by;
- transactions;
- savepoints;
- optimistic locking;
- raw SQL parametrizado;
- SQL preview;
- explain;
- PostgreSQL dialect;
- JSONB básico;
- arrays básicos;
- multi-schema PostgreSQL;
- migration engine;
- migration deploy;
- migration status;
- migration checksums;
- migration locking;
- destructive-change warnings;
- drift detection;
- `db pull`;
- `db push`;
- seed;
- CLI multiplataforma;
- Gradle plugin;
- Maven plugin;
- Spring Boot starter;
- integração com `@Transactional`;
- logging;
- slow query detection;
- metrics hooks;
- Studio básico;
- testing helpers;
- documentação oficial;
- exemplos reais;
- benchmarks;
- migration safety tests;
- integração CI/CD documentada.

---

# 124. O que pode ficar depois do 1.0

Para não atrasar indefinidamente a primeira release:

- R2DBC/reactive runtime;
- MySQL;
- SQLite;
- SQL Server;
- advanced full-text search API;
- generated typed SQL files, se não couber no 1.0;
- automatic query optimisation;
- distributed cache;
- advanced Studio explain visualizer;
- plugin marketplace;
- schema registry remoto;
- managed cloud service;
- database branching;
- multi-tenant policy engine;
- encrypted fields automáticos;
- GraphQL generation;
- REST generation.

O ORM deve manter foco.

---

# 125. Roadmap de desenvolvimento

## Fase 0: Fundação

Objectivo: provar arquitectura.

Entregas:

- repositório;
- build multi-module;
- coding standards;
- CI;
- parser mínimo;
- AST;
- validator mínimo;
- PostgreSQL integration tests.

---

## Fase 1: Schema e generator

Entregas:

- models;
- tipos básicos;
- IDs;
- unique;
- defaults;
- enums;
- relations;
- code generation;
- Gradle generated source integration.

Marco:

```bash
tuprel generate
```

produz um client Java compilável.

---

## Fase 2: Query runtime

Entregas:

- datasource;
- JDBC execution;
- create;
- findById;
- findUnique;
- findMany;
- update;
- delete;
- filters;
- order;
- pagination;
- error mapping.

Marco: CRUD completo sem migrações.

---

## Fase 3: Relações e transacções

Entregas:

- 1:1;
- 1:N;
- N:N;
- includes;
- batching de relações;
- transactions;
- savepoints;
- locking;
- optimistic locking.

---

## Fase 4: Migration engine

Entregas:

- DDL model;
- schema diff;
- create migration;
- dev migration;
- deploy;
- history;
- checksums;
- locks;
- warnings;
- status;
- drift.

---

## Fase 5: Introspection

Entregas:

- PostgreSQL catalogue reader;
- `db pull`;
- `db push`;
- schema merge strategy.

---

## Fase 6: Spring Boot + build tools

Entregas:

- starter;
- DataSource integration;
- `@Transactional`;
- Gradle plugin estável;
- Maven plugin;
- Actuator/Micrometer integration.

---

## Fase 7: Production features

Entregas:

- cursor pagination;
- streaming;
- batch optimisations;
- SQL preview;
- explain;
- slow query detector;
- metrics;
- JSONB;
- PostgreSQL arrays;
- multi-schema;
- hardening.

---

## Fase 8: Studio

Entregas:

- browser de dados;
- filtros;
- edição;
- relations;
- migration history;
- read-only mode.

---

## Fase 9: 1.0 hardening

Entregas:

- documentation freeze;
- API review;
- compatibility suite;
- security review;
- benchmark baseline;
- migration torture tests;
- Windows/macOS/Linux CLI tests;
- upgrade guides;
- release candidate.

---

# 126. Política de estabilidade

Antes do 1.0:

- APIs podem mudar;
- schema syntax pode evoluir;
- migrations devem ser cuidadosamente versionadas.

Depois do 1.0:

- semantic versioning;
- deprecation antes de remoção;
- migration format versionado;
- generated-code compatibility policy;
- upgrade guide para mudanças importantes.

---

# 127. Versionamento do schema format

O formato interno de migration e metadata deverá ter uma versão.

Exemplo:

```text
tuprelSchemaVersion = 1
```

Isto permitirá evoluir ferramentas sem interpretar ficheiros antigos incorrectamente.

---

# 128. Estratégia de releases

Proposta:

```text
0.1.0  parser + codegen preview
0.2.0  CRUD runtime
0.3.0  relations + transactions
0.4.0  migrations preview
0.5.0  Spring Boot + Gradle/Maven
0.6.0  introspection
0.7.0  production query features
0.8.0  Studio
0.9.0  API freeze / release candidate
1.0.0  first stable release
```

As versões reais devem depender da qualidade, não apenas de calendário.

---

# 129. Documentação necessária

A documentação oficial deverá conter:

```text
docs/
├── getting-started/
├── schema/
├── queries/
├── relations/
├── transactions/
├── migrations/
├── introspection/
├── spring-boot/
├── gradle/
├── maven/
├── postgres/
├── studio/
├── testing/
├── performance/
├── deployment/
├── troubleshooting/
└── reference/
```

---

# 130. Getting Started obrigatório

Um novo developer deve conseguir em menos de alguns minutos:

1. adicionar Tuprel;
2. executar `tuprel init`;
3. definir `User`;
4. executar migration;
5. gerar client;
6. criar um registo;
7. consultar registos.

Se o tutorial inicial precisar de dezenas de conceitos antes da primeira query, a experiência está demasiado complexa.

---

# 131. Exemplos oficiais

Criar repositórios de exemplo:

```text
examples/
├── java-standalone/
├── spring-boot-rest-api/
├── spring-boot-transactions/
├── gradle-kotlin-dsl/
├── maven/
├── pagination/
├── jsonb/
├── batch-import/
└── zero-downtime-migrations/
```

Exemplos devem ser executados em CI para não ficarem desactualizados.

---

# 132. Developer experience como requisito de engenharia

O Tuprel só será realmente valioso se for agradável de utilizar.

Isto significa que DX não é apenas design visual ou documentação.

Inclui:

- nomes previsíveis;
- autocomplete;
- erros úteis;
- poucos passos;
- boas defaults;
- comandos rápidos;
- SQL visível;
- generated code estável;
- fácil integração no build;
- migrations compreensíveis;
- documentação com exemplos reais.

---

# 133. Decisões importantes que não devemos quebrar

## Decisão 1

`schema.tuprel` é a fonte de verdade para modelação Tuprel.

## Decisão 2

Generated code não vive misturado com o código escrito pelo developer.

## Decisão 3

O core não depende de Spring.

## Decisão 4

PostgreSQL recebe suporte profundo antes de expandir dialectos.

## Decisão 5

Sem lazy loading implícito.

## Decisão 6

Sem dirty checking automático por omissão.

## Decisão 7

Writes são explícitos.

## Decisão 8

SQL raw existe como escape hatch.

## Decisão 9

Migrations de produção são conservadoras.

## Decisão 10

Performance e correctness são medidas por testes e benchmarks.

---

# 134. Exemplo de experiência completa de desenvolvimento

Criar Tuprel:

```bash
tuprel init
```

Editar:

```text
tuprel/schema.tuprel
```

Validar:

```bash
tuprel validate
```

Formatar:

```bash
tuprel format
```

Criar migration:

```bash
tuprel migrate dev --name init
```

Gerar client:

```bash
tuprel generate
```

Executar testes:

```bash
./gradlew test
```

Abrir dados:

```bash
tuprel studio
```

Ver estado:

```bash
tuprel migrate status
```

Deployment:

```bash
tuprel migrate deploy
java -jar app.jar
```

---

# 135. Exemplo de alteração de schema

Antes:

```tuprel
model User {
    id    UUID   @id @default(uuid())
    name  String
    email String @unique
}
```

Depois:

```tuprel
model User {
    id        UUID      @id @default(uuid())
    name      String
    email     String    @unique
    active    Boolean   @default(true)
    createdAt Instant   @default(now())
}
```

Executar:

```bash
tuprel migrate dev --name add-user-metadata
```

Tuprel mostra:

```text
Changes:
+ users.active BOOLEAN NOT NULL DEFAULT TRUE
+ users.created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

Depois cria SQL legível.

---

# 136. Exemplo de aviso destrutivo

Developer remove:

```tuprel
legacyCode String?
```

CLI:

```text
Potentially destructive change detected.

- Column users.legacy_code will be dropped.
- Existing values in this column will be permanently removed.

Create migration anyway? [y/N]
```

Em modo não interactivo, a operação falha até receber flag explícita adequada.

---

# 137. Exemplo de erro de relação

Schema:

```tuprel
model Order {
    user   User @relation(fields: [userId], references: [id])
    userId String
}

model User {
    id UUID @id
}
```

Erro:

```text
TUPREL-SCHEMA-221
Relation type mismatch.

Order.userId is String.
User.id is UUID.

Both sides of a foreign-key relation must use compatible types.
```

---

# 138. Exemplo de aplicação Spring Boot

```java
@RestController
@RequestMapping("/users")
public final class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<User> list(
        @RequestParam(defaultValue = "20") int limit
    ) {
        return userService.list(limit);
    }
}
```

```java
@Service
public final class UserService {

    private final TuprelClient db;

    public UserService(TuprelClient db) {
        this.db = db;
    }

    public List<User> list(int limit) {
        return db.user().findMany(q -> q
            .where(UserWhere.active().eq(true))
            .orderBy(UserOrder.createdAt().desc())
            .take(limit)
        );
    }
}
```

Nenhum repository vazio é obrigatório.

Se a equipa quiser uma camada repository por razões de domínio, pode criá-la normalmente.

---

# 139. Arquitectura de dependências

O objectivo é impedir dependências circulares entre módulos.

Exemplo:

```text
schema-ast
   ↑
schema-parser
   ↑
schema-validator

sql-ast
   ↑
dialect-api
   ↑
postgresql

runtime
 ├── sql-ast
 ├── dialect-api
 └── jdbc

codegen
 ├── schema-ast
 └── codegen-java

migrations
 ├── schema-ast
 ├── dialect-api
 └── introspection
```

CLI pode depender dos serviços de alto nível, mas o core não deve depender da CLI.

---

# 140. Interfaces SPI importantes

## Dialect

```java
public interface TuprelDialect {
    SqlRenderResult render(QueryAst query);
    TypeMapping types();
    DdlRenderer ddl();
    Introspector introspector();
}
```

## Query listener

```java
public interface QueryListener {
    void beforeQuery(QueryEvent event);
    void afterQuery(QueryCompletedEvent event);
    void queryFailed(QueryFailedEvent event);
}
```

## Naming strategy

```java
public interface NamingStrategy {
    String tableName(String modelName);
    String columnName(String fieldName);
}
```

SPIs devem ser pequenas e versionáveis.

---

# 141. Anti-objectivos de API

Evitar APIs como:

```java
db.query("User")
  .where("email", "=", email)
```

porque perde type safety.

Evitar exigir:

```java
new UserRepositoryImpl(...)
```

para cada modelo quando o comportamento é igual.

Evitar hidden global state.

Evitar static singleton obrigatório.

Evitar annotations repetindo todo o schema.

---

# 142. Política de raw SQL

Raw SQL é uma ferramenta legítima.

O Tuprel não deverá envergonhar o developer por precisar de SQL.

A documentação deve dizer claramente:

- use a API type-safe para operações comuns;
- use raw SQL para casos específicos;
- parametrizar sempre valores;
- preferir typed SQL generation quando disponível;
- medir queries complexas com EXPLAIN.

---

# 143. Política de migrações manuais

O migration.sql gerado deverá poder ser revisto antes de aplicar.

Casos avançados exigem SQL manual:

- backfills;
- triggers;
- functions;
- índices CONCURRENTLY;
- transforms de dados;
- extensão PostgreSQL;
- migrations por etapas.

O Tuprel deverá aceitar isso em vez de tentar impedir.

---

# 144. Extensões PostgreSQL

A primeira versão deverá pelo menos tolerar bases que utilizam extensões.

Futuro:

```tuprel
extension "pgcrypto"
extension "citext"
```

Não precisa de entrar no primeiro milestone se complicar demasiado o migration engine.

---

# 145. CITEXT e tipos específicos

Tipos específicos PostgreSQL podem ser expostos por annotations/attributes de dialecto.

Exemplo conceptual:

```tuprel
email String @db.Citext @unique
```

Isto permite suporte profundo sem poluir o core universal.

---

# 146. Segurança operacional

Comandos perigosos devem reconhecer ambiente.

Configuração:

```toml
[environment]
name = "production"
```

Ou flag/env:

```text
TUPREL_ENV=production
```

Em produção:

- `migrate reset`: recusado;
- `db push --accept-data-loss`: recusado por omissão;
- Studio write mode: warning forte ou desactivado;
- debug parameter logging: desactivado.

---

# 147. Telemetria

A primeira versão pode funcionar sem telemetria.

Se algum dia houver telemetria:

- opt-in explícito;
- documentação clara;
- sem SQL do utilizador;
- sem dados de negócio;
- sem connection strings;
- possibilidade de desligar completamente.

---

# 148. Licença

Para adopção open source, uma licença permissiva como Apache-2.0 ou MIT deverá ser considerada.

Apache-2.0 oferece cláusulas explícitas relacionadas com patentes e pode ser interessante para um projecto de infraestrutura.

A decisão final deve ser tomada antes da primeira release pública relevante.

---

# 149. Governança open source

Repositório deverá ter:

```text
README.md
CONTRIBUTING.md
CODE_OF_CONDUCT.md
SECURITY.md
LICENSE
CHANGELOG.md
ROADMAP.md
```

Também:

- issue templates;
- bug template;
- feature request template;
- RFC process para mudanças grandes;
- conventional commits ou política equivalente;
- release notes.

---

# 150. RFCs

Mudanças importantes devem usar RFC.

Exemplos:

```text
RFC-001 Schema Language
RFC-002 Query API
RFC-003 Relation Loading
RFC-004 Migration Format
RFC-005 Spring Transaction Integration
RFC-006 Dialect SPI
```

Isto evita decisões arquitecturais grandes tomadas apenas num pull request isolado.

---

# 151. Segurança do projecto

`SECURITY.md` deverá explicar:

- como reportar vulnerabilidades;
- prazo esperado de resposta;
- versões suportadas;
- processo de disclosure.

Dependências deverão ser verificadas automaticamente.

---

# 152. Definição de qualidade para 1.0

O Tuprel 1.0 não é apenas uma lista de funcionalidades.

Para ser estável deve cumprir:

- zero known critical data corruption bugs;
- migration test suite extensa;
- transaction tests sob concorrência;
- compatibility tests em versões Java suportadas;
- PostgreSQL version matrix definida;
- exemplos oficiais executados em CI;
- documentação de todas as APIs públicas;
- upgrade path a partir da última 0.x;
- benchmark baseline publicado;
- security review interna;
- release candidate utilizada em aplicações reais antes do final.

---

# 153. Critérios de aceitação por funcionalidade

Uma funcionalidade só é considerada pronta quando tem:

1. API implementada;
2. testes unitários quando aplicável;
3. integration tests;
4. mensagens de erro;
5. documentação;
6. exemplo;
7. changelog;
8. comportamento em Windows/Linux/macOS quando se aplica à CLI;
9. compatibilidade com Spring quando relevante;
10. benchmark quando é performance-sensitive.

---

# 154. Possível organização de packages gerados

```text
com.example.app.db
├── TuprelClient
├── model
├── create
├── update
├── where
├── order
├── include
├── fields
├── aggregate
└── internal
```

O package `internal` não deve fazer parte da API pública.

---

# 155. API pública vs interna

A biblioteca deve separar claramente:

```text
dev.tuprel.api.*
```

estável, de:

```text
dev.tuprel.internal.*
```

sem garantia de compatibilidade.

JPMS modules podem reforçar esta separação posteriormente.

---

# 156. Java Module System

Suporte JPMS pode ser preparado desde cedo com module descriptors nos módulos core.

Não deverá bloquear adopção tradicional pelo classpath.

---

# 157. Dependências

Objectivo:

- core com poucas dependências;
- evitar trazer uma framework web;
- evitar logging implementation obrigatória;
- usar facade de logging adequada;
- dependências opcionais separadas por integração.

Um ORM deve evitar transformar-se numa dependency tree gigante.

---

# 158. CLI implementation

A CLI pode ser escrita em Java e compilada também como native executable.

Objectivos:

- arranque rápido;
- instalação simples;
- boa experiência em Windows;
- shell completions;
- mensagens com e sem cor;
- `--json` em comandos importantes para automação.

Exemplo:

```bash
tuprel migrate status --json
```

---

# 159. Output machine-readable

Para CI e tooling:

```bash
tuprel validate --json
tuprel migrate status --json
tuprel doctor --json
```

Isto permite IDE plugins e automação futura.

---

# 160. IDE plugin futuro

Não é requisito para 1.0, mas o desenho da CLI e schema deverá permitir:

- syntax highlighting;
- autocomplete do schema;
- go to model;
- relation navigation;
- validation inline;
- run generate;
- migration diff preview.

Uma Language Server Protocol implementation pode ser mais sustentável do que plugins totalmente separados.

---

# 161. LSP futuro

Um `tuprel language-server` poderia alimentar:

- IntelliJ;
- VS Code;
- outros editores.

O parser deverá guardar source spans desde o início para facilitar esta evolução.

---

# 162. Schema comments e documentação

```tuprel
/// Utilizador registado na plataforma.
model User {
    /// Identificador global.
    id UUID @id @default(uuid())

    /// Endereço de email único.
    email String @unique
}
```

O generator pode transformar comentários em Javadoc.

Isto melhora a experiência no IDE.

---

# 163. Deprecation de fields

Futuro:

```tuprel
legacyCode String? @deprecated("Use externalId")
```

Generated code pode emitir `@Deprecated`.

Útil em migrations por etapas.

---

# 164. Database comments

Opcionalmente, comentários do schema podem ser sincronizados para PostgreSQL `COMMENT ON`.

Deve ser configurável porque nem todas as equipas desejam essa sincronização.

---

# 165. Segurança de dados sensíveis

Pode existir metadata:

```tuprel
passwordHash String @sensitive
```

O objectivo não é encriptar automaticamente no 1.0.

Serve para:

- mascarar parâmetros em logs;
- esconder valor no Studio por omissão;
- evitar debug accidental.

Também pode ser usado em:

```tuprel
apiToken String @sensitive
```

Esta funcionalidade seria muito útil e relativamente pequena.

---

# 166. PII metadata opcional

Futuro:

```tuprel
email String @pii
```

Pode ajudar tooling de auditoria, mas não deve introduzir promessas legais automáticas.

O ORM não pode afirmar conformidade regulatória apenas porque existe uma annotation.

---

# 167. Soft delete e uniqueness

O Tuprel deverá documentar que soft delete pode interagir com unique constraints.

Exemplo: email único continua bloqueado mesmo depois de soft delete se o índice for global.

A solução pode exigir índice parcial PostgreSQL.

O Studio/migration docs devem tornar estes casos visíveis.

---

# 168. Relações e cascade

Cascade delete deve exigir declaração no schema.

Nada deve assumir `Cascade` silenciosamente.

Defaults conservadoras:

- `onDelete: Restrict` ou equivalente seguro;
- developer escolhe Cascade quando necessário.

---

# 169. Update de relações

A API deve permitir operações explícitas:

```java
db.user().update(q -> q
    .where(UserWhere.id().eq(userId))
    .connect(UserRelations.team(), teamId)
);
```

Ou uma API semelhante.

Também:

- connect;
- disconnect;
- set;
- create related;
- delete related, quando permitido.

As operações exactas devem ser desenhadas para evitar ambiguity.

---

# 170. Nested writes

Nested writes são úteis mas complexas.

Exemplo desejado:

```java
db.user().create(data -> data
    .name("Ana")
    .profile(profile -> profile
        .bio("Backend developer")
    )
);
```

Deverá correr numa transacção.

Pode entrar no 1.0 apenas depois de CRUD, relações e transactions estarem sólidos.

---

# 171. Idempotência de migrations

As migrations são aplicadas exactamente uma vez segundo o histórico Tuprel.

O SQL individual não precisa de ser escrito com `IF NOT EXISTS` em todos os casos.

O migration table e checksums são a fonte de controlo.

---

# 172. Renames

Schema diff não consegue sempre distinguir:

```text
column old_name removed
column new_name added
```

versus:

```text
rename old_name -> new_name
```

Para evitar perda de dados, o CLI deverá perguntar ou permitir declaração explícita.

Exemplo:

```bash
tuprel migrate dev --name rename-user-name
```

CLI:

```text
Possible rename detected:
users.full_name -> users.display_name

Treat as rename? [Y/n]
```

Em CI, renames devem ser declarados em migration manual ou metadata explícita.

---

# 173. Data migrations

Nem toda migration é schema migration.

O Tuprel deverá permitir SQL manual dentro da migration:

```sql
UPDATE users
SET status = 'ACTIVE'
WHERE status IS NULL;
```

Para lógica complexa, o projecto pode suportar migration hooks Java no futuro, mas SQL deve ser suficiente inicialmente.

---

# 174. Migration transactions

Por omissão, migrations PostgreSQL devem executar dentro de transaction quando todas as statements o permitem.

Algumas operações, como certas formas de `CREATE INDEX CONCURRENTLY`, não podem ser executadas numa transaction normal.

A migration deverá poder declarar:

```text
transaction = false
```

com warning claro.

---

# 175. Connection URLs

O Tuprel deverá suportar JDBC URLs e uma forma simplificada de URL de base de dados, desde que a normalização seja bem definida.

Exemplo:

```text
jdbc:postgresql://localhost:5432/app
```

Nunca imprimir password em logs.

---

# 176. Configuração por ambiente

Possibilidade:

```toml
[profiles.dev]
urlEnv = "DEV_DATABASE_URL"

[profiles.test]
urlEnv = "TEST_DATABASE_URL"
```

Uso:

```bash
tuprel migrate dev --profile dev
```

Em aplicações Spring, runtime continua a poder usar o DataSource da aplicação.

---

# 177. `.env`

A CLI pode carregar `.env` em desenvolvimento por conveniência.

Regras:

- nunca criar segredos reais automaticamente;
- `.env` deve estar no `.gitignore` por omissão;
- `.env.example` pode ser criado.

---

# 178. Project init interactivo

```bash
tuprel init
```

Possível diálogo:

```text
Tuprel ORM

Detected:
  Build: Gradle
  Framework: Spring Boot
  Java: 21

Database:
  > PostgreSQL

Schema path:
  > tuprel/schema.tuprel

Java package:
  > com.example.app.db

✓ Created tuprel/schema.tuprel
✓ Created tuprel.toml
✓ Added Tuprel generated sources configuration
✓ Tuprel is ready
```

Deve existir modo não interactivo:

```bash
tuprel init --database postgresql --package com.example.app.db --yes
```

---

# 179. Modificação automática de build files

O CLI pode oferecer:

```text
Add Tuprel Gradle plugin to build.gradle.kts? [Y/n]
```

Não deve alterar ficheiros de build silenciosamente sem mostrar o que vai fazer.

Alternativa:

```bash
tuprel init --no-build-changes
```

---

# 180. Compatibilidade com Kotlin

O primeiro target de código gerado é Java.

Aplicações Kotlin na JVM poderão consumir as classes Java geradas.

Um generator Kotlin nativo pode surgir depois, mas não deve atrasar o 1.0.

---

# 181. Compatibilidade com Lombok

O Tuprel não deverá depender de Lombok.

Generated code deve compilar com Java puro.

Developers podem usar Lombok no resto da aplicação normalmente.

---

# 182. Serialização JSON

Os modelos gerados não devem depender obrigatoriamente de Jackson.

Spring applications podem serializá-los se a estrutura for compatível.

Integrações específicas com Jackson podem ser opcionais.

O ORM não deve obrigar uma stack HTTP.

---

# 183. Domain models vs database models

O Tuprel não deve afirmar que o modelo gerado é sempre o modelo de domínio ideal.

Em sistemas simples, pode ser usado directamente.

Em sistemas complexos:

```text
Tuprel model
    ↓
Repository/application adapter
    ↓
Domain object
```

A ferramenta deve apoiar ambos os estilos.

---

# 184. Repository pattern opcional

O Tuprel não elimina o Repository Pattern.

Elimina a obrigação de criar repositories triviais.

Quando existe lógica de acesso a dados complexa, um repository continua útil:

```java
public final class OrderRepository {

    private final TuprelClient db;

    public List<Order> findPendingForProcessing(...) {
        ...
    }
}
```

---

# 185. DDD

O ORM deve funcionar num projecto com Domain-Driven Design sem impor Active Record.

A API principal não deverá obrigar os models a terem métodos `save()` ou `delete()`.

Preferência:

```java
db.user().update(...)
```

em vez de:

```java
user.save();
```

Isso mantém persistência explícita e desacoplada.

---

# 186. Active Record

Não deverá ser o modelo principal do Tuprel 1.0.

Pode surgir como módulo opcional se houver procura.

---

# 187. Multi-tenancy

Não colocar multi-tenancy automático no core 1.0.

Futuro módulo pode suportar:

- database-per-tenant;
- schema-per-tenant;
- shared-table tenant column.

Este tema afecta segurança e deve ser implementado apenas com forte cobertura de testes.

---

# 188. Row Level Security

PostgreSQL RLS deve continuar a funcionar porque o Tuprel usa SQL normal.

Uma integração específica pode surgir posteriormente.

O 1.0 deve documentar como usar connection/session variables quando necessário.

---

# 189. Generated schema diagram

Comando útil:

```bash
tuprel schema graph
```

Pode produzir Mermaid:

```bash
tuprel schema graph --format mermaid > schema.mmd
```

Ou SVG no futuro.

Isto é útil para documentação e onboarding.

---

# 190. `tuprel info`

Saída:

```text
Tuprel CLI: 1.0.0
Java: 21.0.x
Build tool: Gradle 9.x
Database provider: PostgreSQL
Schema: tuprel/schema.tuprel
Models: 18
Migrations: 24
Generated package: com.example.app.db
```

Não deve revelar connection password.

---

# 191. Compatibilidade entre CLI e runtime

O Tuprel deverá detectar incompatibilidades importantes.

Exemplo:

```text
Tuprel CLI 1.3 cannot generate client for runtime 1.0.

Detected runtime: 1.0.4
Recommended CLI: 1.0.x
```

Ou suportar um intervalo documentado.

---

# 192. Lockfile

`tuprel/migration.lock` pode guardar:

```text
provider = "postgresql"
formatVersion = 1
```

Não deve guardar credenciais.

---

# 193. Schema fingerprint

O generator pode calcular um fingerprint do schema e inseri-lo em metadata gerado.

O runtime pode opcionalmente comparar versão esperada com migration state em desenvolvimento.

Não deve executar introspecção pesada em cada startup de produção.

---

# 194. Health checks

Spring Boot starter pode fornecer:

```text
tuprel
status: UP
provider: postgresql
```

Por omissão o health check deve ser barato.

Pode existir deep health opcional que executa `SELECT 1`.

---

# 195. Read replicas

Uma versão 1.x futura pode permitir:

```text
primary datasource
replica datasource(s)
```

Reads marcados podem ir para réplica.

Não colocar routing automático complexo no primeiro 1.0.

---

# 196. Connection context

A API deve permitir usar uma connection/transacção externa em integrações avançadas, sem tornar o caminho normal complexo.

---

# 197. DataSource ownership

Regra:

- se Tuprel cria o DataSource, Tuprel fecha-o;
- se a aplicação fornece o DataSource, Tuprel não deve assumir ownership.

Isto deve estar documentado.

---

# 198. API de shutdown

Standalone:

```java
try (TuprelClient db = ...) {
    ...
}
```

Spring:

O lifecycle do bean trata cleanup dos recursos pertencentes ao Tuprel.

---

# 199. Logging facade

O core deve utilizar uma facade de logging adequada e não obrigar uma implementação específica.

Aplicações Spring usam a configuração existente.

CLI pode usar renderer próprio.

---

# 200. Formato de logs

Eventos estruturados devem poder ser emitidos em JSON quando configurado.

Campos:

```text
operation
model
duration_ms
rows
success
sql_state
trace_id
```

Nunca password ou connection string completa.

---

# 201. Padrão de IDs

O Tuprel deve suportar:

- UUID;
- identity/serial equivalent;
- assigned IDs;
- composite IDs.

Exemplo:

```tuprel
id Long @id @default(identity())
```

ou:

```tuprel
id UUID @id @default(uuid())
```

---

# 202. UUID generation

A semântica deve indicar onde o UUID é gerado:

- client-side;
- database-side.

Pode haver funções diferentes se necessário.

Exemplo conceptual:

```tuprel
@default(uuid())
@default(dbUuid())
```

A nomenclatura final deve ser decidida numa RFC.

---

# 203. Decimal precision

Schema deve permitir:

```tuprel
price Decimal @db.Decimal(12, 2)
```

O generator usa `BigDecimal`.

Nunca converter dinheiro automaticamente para `double`.

---

# 204. String length

```tuprel
name String @db.VarChar(150)
description String @db.Text
```

Defaults devem ser documentados.

---

# 205. Timestamps e timezone

O Tuprel deve distinguir claramente:

- `Instant` para momento absoluto;
- `LocalDateTime` para data/hora sem timezone;
- `LocalDate`;
- `LocalTime`.

Isto evita mapeamentos temporais ambíguos.

---

# 206. Generated metadata

Cada model terá metadata estático utilizado pelo runtime:

```java
UserMetadata.TABLE
UserMetadata.ID
UserMetadata.EMAIL
```

Pode ser package-internal para não poluir a API pública.

---

# 207. Statement naming/fingerprinting

Para observabilidade, queries podem receber fingerprint sem valores:

```text
SELECT users WHERE email = ?
```

Ajuda a agrupar métricas sem expor PII.

---

# 208. N+1 diagnostics

Em development mode, se forem observadas dezenas de queries estruturalmente idênticas dentro de uma request/trace, Tuprel pode emitir:

```text
Possible N+1 query pattern detected.
42 queries executed for Product.seller.
Consider using include(UserInclude...)
```

Esta funcionalidade deve ser heurística e apresentada como warning, não como certeza absoluta.

---

# 209. Desenvolvimento local com Docker

A documentação oficial deve incluir exemplo:

```yaml
services:
  postgres:
    image: postgres
    environment:
      POSTGRES_DB: app
      POSTGRES_USER: app
      POSTGRES_PASSWORD: app
    ports:
      - "5432:5432"
```

O Tuprel não precisa de gerir Docker internamente.

---

# 210. Test database safety

Comandos como reset devem verificar host/database e environment.

Um teste nunca deve resetar production acidentalmente.

O testing toolkit pode exigir explicitamente:

```text
TUPREL_TEST_DATABASE_URL
```

---

# 211. Transaction boundaries e async

Não permitir que um transaction client seja usado depois do callback terminar.

Se o developer tentar:

```java
TuprelTransaction leaked;
```

fora do lifetime, deverá falhar claramente.

---

# 212. Query immutability

Builders podem ser mutáveis durante construção, mas queries compiladas devem ser imutáveis para thread safety e cache interno.

---

# 213. API consistency

Todos os delegates devem seguir a mesma nomenclatura:

```text
create
createMany
findById
findUnique
findMany
update
updateMany
delete
deleteMany
upsert
count
aggregate
groupBy
stream
```

Evitar nomes diferentes por model.

---

# 214. Result semantics

Definir claramente:

- `findById`: `Optional<T>`;
- `findUnique`: `Optional<T>`;
- `findFirst`: `Optional<T>`;
- `create`: `T`;
- `update`: `T` ou not-found exception conforme API;
- `delete`: `T` ou affected count conforme versão;
- `updateMany`: `long` affected count;
- `deleteMany`: `long` affected count.

Consistência é mais importante do que tentar imitar qualquer API externa.

---

# 215. Find or throw

Atalhos:

```java
User user = db.user().findByIdOrThrow(userId);
```

ou:

```java
.findUniqueOrThrow(...)
```

Excepção:

```text
RecordNotFoundException
```

---

# 216. Query comments

Para diagnóstico em produção:

```java
db.order().findMany(q -> q
    .comment("checkout:load-open-orders")
    .where(...)
);
```

Renderiza comentário SQL seguro quando activado.

Ajuda a relacionar query com aplicação.

---

# 217. Application name

A configuração pode passar application name para PostgreSQL.

Útil em `pg_stat_activity`.

---

# 218. Schema namespaces gerados

Para evitar colisões de nomes Java, o generator deverá tratar models com nomes iguais em diferentes database schemas de forma definida.

---

# 219. Reserved words

O validator deve detectar nomes que entram em conflito com:

- keywords Java;
- classes geradas;
- reserved names da DSL.

Pode sugerir `@map` quando a coluna SQL usa um nome problemático.

---

# 220. API de extensões

PostgreSQL-specific query operators podem viver num namespace separado para manter o core limpo.

Exemplo conceptual:

```java
PostgresJson.where(...)
PostgresArray.where(...)
```

ou ser gerados apenas quando o field utiliza tipo correspondente.

---

# 221. Design review antes de implementar

Antes de escrever o runtime completo, criar RFCs para:

1. schema grammar;
2. Java generated API;
3. relation loading semantics;
4. migration history format;
5. transaction semantics;
6. nullable representation;
7. projections;
8. dialect SPI.

Essas decisões são difíceis de mudar depois de utilizadores adoptarem o ORM.

---

# 222. MVP técnico realista

Embora este documento descreva um ORM robusto, o primeiro milestone deve ser pequeno.

### MVP 0.1

Schema:

- PostgreSQL;
- String;
- Int;
- Long;
- Boolean;
- UUID;
- Instant;
- Decimal;
- required/optional;
- `@id`;
- `@unique`;
- `@default`;
- `@@index`;
- 1:N.

Runtime:

- connect;
- create;
- findById;
- findMany;
- update;
- delete;
- eq filters;
- order;
- limit.

Tooling:

- `tuprel init`;
- `tuprel validate`;
- `tuprel generate`;
- Gradle integration.

Este milestone prova a arquitectura sem tentar entregar o produto inteiro.

---

# 223. MVP 0.2

Adicionar:

- relações completas;
- transactions;
- filters avançados;
- pagination;
- errors;
- Maven;
- Spring Boot basic starter.

---

# 224. MVP 0.3

Adicionar migration engine inicial:

- init migration;
- add table;
- add column;
- index;
- foreign key;
- status;
- history.

---

# 225. Como saber se o produto está a resolver um problema real

Antes de 1.0, testar Tuprel em pelo menos três tipos de projecto:

1. API CRUD pequena;
2. aplicação Spring Boot com relações e transacções;
3. aplicação com base existente, migrations e queries complexas.

Métricas qualitativas:

- quanto código repetitivo foi eliminado;
- quantas vezes foi necessário raw SQL;
- onde a API ficou difícil;
- tempo de onboarding;
- qualidade das mensagens de erro;
- dificuldade de debugging;
- performance versus JDBC directo;
- estabilidade de migrations.

---

# 226. Exemplos de funcionalidades que não devem ser adicionadas apenas para parecer completo

Evitar construir cedo demais:

- cache distribuída;
- query AI;
- auto-index AI;
- code generation de controllers;
- autenticação;
- API REST automática;
- GraphQL server;
- queue system;
- cloud database própria.

Primeiro ganhar confiança em persistência.

---

# 227. Possível futura inteligência de diagnóstico

Depois de o core estar maduro, Tuprel pode analisar telemetria e sugerir:

```text
Query returned 100,000 rows without pagination.
```

```text
Filter uses users.email but no matching index was detected.
```

```text
Possible sequential scan on a frequently executed query.
```

```text
Relation include produced a large cartesian result.
```

Estas sugestões devem basear-se em dados reais e nunca alterar schema automaticamente em produção.

---

# 228. Segurança de auto-optimisation

Qualquer ferramenta futura de optimização deve sugerir primeiro.

Nunca criar ou eliminar índices automaticamente em produção sem aprovação.

---

# 229. Tuprel Studio como ferramenta de aprendizagem

O Studio pode ensinar SQL sem esconder SQL.

Ao executar uma pesquisa visual:

```text
User
active = true
order by createdAt desc
```

pode mostrar:

```sql
SELECT ...
FROM users
WHERE active = true
ORDER BY created_at DESC;
```

Isto transforma a ferramenta numa ponte entre produtividade e compreensão da base de dados.

---

# 230. Visão de longo prazo

Tuprel pode tornar-se um ecossistema:

```text
Tuprel ORM
├── Tuprel Schema
├── Tuprel Client
├── Tuprel Migrate
├── Tuprel Studio
├── Tuprel CLI
├── Tuprel Spring
├── Tuprel Gradle
├── Tuprel Maven
├── Tuprel Test
├── Tuprel PostgreSQL
└── Future Dialects
```

Mas todos os módulos devem continuar ligados a uma missão única:

> tornar a persistência em Java simples de iniciar, segura de evoluir e previsível em produção.

---

# 231. Manifesto técnico do Tuprel

O Tuprel deverá defender as seguintes ideias:

1. O developer merece autocomplete na camada de dados.
2. O schema da aplicação deve ser fácil de ler.
3. Migrações devem fazer parte do workflow normal.
4. SQL não é inimigo do ORM.
5. Queries escondidas são perigosas.
6. Relações devem ser explícitas.
7. Uma alteração de dados deve ser explícita.
8. Erros devem explicar como ser corrigidos.
9. Production safety é mais importante do que conveniência destrutiva.
10. O ORM deve integrar-se com Java, não tentar substituir o ecossistema Java.
11. Spring Boot deve ser excelente, mas não obrigatório.
12. A base de dados continua a ser a autoridade final de integridade.
13. Performance deve ser medida, não presumida.
14. Features avançadas não podem destruir a simplicidade do caso comum.
15. O developer deve conseguir sempre chegar ao SQL quando precisa.

---

# 232. Checklist antes da primeira linha de implementação séria

- [ ] confirmar nome final;
- [ ] confirmar groupId/package root;
- [ ] criar repositório;
- [ ] escolher licença;
- [ ] escrever RFC-001 Schema Language;
- [ ] escrever grammar inicial;
- [ ] escrever RFC-002 Generated Java API;
- [ ] escrever RFC-003 Relation Loading;
- [ ] definir Java mínimo;
- [ ] definir PostgreSQL mínimo suportado;
- [ ] configurar CI Linux/Windows/macOS;
- [ ] configurar formatting;
- [ ] configurar static analysis;
- [ ] criar Testcontainers PostgreSQL suite;
- [ ] criar primeiro parser fixture;
- [ ] criar primeiro codegen golden test;
- [ ] criar primeiro end-to-end example;
- [ ] criar benchmark baseline JDBC;

---

# 233. Primeira demonstração pública que o projecto deve conseguir fazer

Schema:

```tuprel
model User {
    id        UUID      @id @default(uuid())
    name      String
    email     String    @unique
    active    Boolean   @default(true)
    posts     Post[]
    createdAt Instant   @default(now())
}

model Post {
    id        UUID      @id @default(uuid())
    title     String
    published Boolean   @default(false)
    authorId  UUID
    author    User      @relation(fields: [authorId], references: [id])
    createdAt Instant   @default(now())
}
```

Comandos:

```bash
tuprel validate
tuprel migrate dev --name init
tuprel generate
```

Java:

```java
User user = db.user().create(data -> data
    .name("Mamadu")
    .email("mamadu@example.com")
);

Post post = db.post().create(data -> data
    .title("Construindo um ORM em Java")
    .authorId(user.id())
);

List<Post> posts = db.post().findMany(q -> q
    .where(PostWhere.published().eq(false))
    .orderBy(PostOrder.createdAt().desc())
    .take(20)
);
```

Esta demonstração deve funcionar end-to-end com PostgreSQL real.

---

# 234. Definição final do produto

**Tuprel ORM** é uma plataforma de persistência para Java, schema-first, type-safe e orientada a geração de código, que unifica modelação, queries, relações, migrações, introspecção e tooling de desenvolvimento numa experiência consistente.

A ferramenta foi concebida para resolver cinco problemas centrais:

1. reduzir boilerplate;
2. reduzir fragmentação da camada de dados;
3. detectar erros mais cedo;
4. tornar o comportamento de queries previsível;
5. tornar a evolução da base de dados mais segura.

O Tuprel não deverá esconder a base de dados. Deverá tornar a base de dados mais fácil de utilizar correctamente.

A experiência final pretendida é simples:

```text
Definir
  ↓
Validar
  ↓
Migrar
  ↓
Gerar
  ↓
Consultar
  ↓
Observar
  ↓
Evoluir
```

Se o projecto conseguir preservar esta simplicidade ao mesmo tempo que suporta transacções, concorrência, migrations, debugging, performance e aplicações reais, então deixará de ser apenas "mais um ORM" e passará a ser uma ferramenta de infraestrutura Java com valor próprio.

---

# 235. Próximos documentos recomendados

Este documento define a visão global. Antes da implementação completa, devem ser criados documentos especializados:

```text
docs/rfcs/RFC-001-schema-language.md
docs/rfcs/RFC-002-java-client-api.md
docs/rfcs/RFC-003-relations.md
docs/rfcs/RFC-004-migrations.md
docs/rfcs/RFC-005-transactions.md
docs/rfcs/RFC-006-postgresql-dialect.md
docs/rfcs/RFC-007-code-generation.md
docs/rfcs/RFC-008-spring-integration.md
docs/rfcs/RFC-009-studio.md
docs/rfcs/RFC-010-testing-strategy.md
```

A implementação deverá começar apenas depois de pelo menos RFC-001, RFC-002 e RFC-003 estarem suficientemente definidas, porque essas decisões moldam quase toda a API pública futura.

---

## Fim do documento
