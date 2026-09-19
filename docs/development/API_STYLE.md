# API Style

## Exemplo de intenção

```java
Optional<User> user = db.user()
    .findUnique(query -> query
        .where(UserWhere.email().eq(email))
    );
```

```java
List<User> users = db.user()
    .findMany(query -> query
        .where(UserWhere.active().eq(true))
        .orderBy(UserOrder.createdAt().desc())
        .take(20)
    );
```

A sintaxe exacta só se torna contrato após a fase de design da query API. Estes snippets mostram princípios:

- tipos de retorno explícitos;
- filtros gerados/type-safe;
- I/O explícito no método;
- sem repository obrigatório por model;
- relações não são carregadas implicitamente.

## Erros

Separar categorias como configuração, schema, query construction, database execution, constraint violation, timeout, migration e introspection. Não expor mensagens JDBC cruas como único contrato público.
