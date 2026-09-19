# Princípios de Produto do Jorvia ORM

## 1. Simples no caso comum, explícito no caso complexo

Criar, procurar, actualizar e eliminar dados deve exigir pouco código. Quando entram joins, locks, transacções, raw SQL, timeout ou migrations destrutivas, a API deve mostrar claramente o que está a acontecer.

## 2. Type safety é parte do produto

O schema deve gerar tipos Java, inputs, filtros, selectors e metadata que deslocam erros para validação e compilação sempre que isso for realisticamente possível.

## 3. Sem magia escondida

O Jorvia não deve disparar queries porque um getter foi acedido. Não deve escrever alterações apenas porque um objecto em memória mudou. Não deve carregar relações sem pedido explícito.

## 4. SQL continua visível

O developer deve conseguir inspeccionar SQL, bind parameters redigidos, duração, query plans e origem lógica da query. Uma abstração de persistência não deve impedir diagnóstico.

## 5. Migrations são código operacional

Migrações precisam de revisão, history, checksum, locking, drift detection e comportamento definido em falhas. Produção não é um local para geração improvisada de schema.

## 6. PostgreSQL primeiro e profundamente

É preferível suportar correctamente PostgreSQL, incluindo JSONB, arrays, UUID, locks, constraints e índices relevantes, do que declarar compatibilidade superficial com muitas bases.

## 7. Framework-agnostic core

Spring Boot deve ter integração excelente, mas o runtime Jorvia deve poder ser usado numa aplicação Java sem Spring.

## 8. Safe by default

APIs seguras devem ser mais fáceis que APIs inseguras. Valores são parametrizados. Secrets são redigidos. Operações destrutivas têm fricção. Raw SQL não transforma automaticamente strings externas em SQL executável.

## 9. Performance mensurável

Optimizações só contam quando existem benchmarks ou evidência. A biblioteca deve expor métricas e evitar overhead escondido.

## 10. Evolução responsável

Uma API simples hoje não pode tornar impossível corrigir semântica amanhã. Contratos públicos, formato de migrations e formato de schema devem ter política de compatibilidade definida antes de 1.0.
