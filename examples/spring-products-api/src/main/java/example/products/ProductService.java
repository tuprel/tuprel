package example.products;

import example.products.generated.TuprelClient;
import example.products.generated.create.ProductCreate;
import example.products.generated.model.Product;
import example.products.generated.order.ProductOrder;
import example.products.generated.update.ProductUpdate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class ProductService {
    private final TuprelClient client;

    ProductService(TuprelClient client) {
        this.client = client;
    }

    ProductResponse create(ProductRequest request) {
        Product product = client.product().create(ProductCreate.builder()
                .id(UUID.randomUUID()).name(request.name()).price(request.price()).stock(request.stock()).build());
        return ProductResponse.from(product);
    }

    Optional<ProductResponse> find(UUID id) {
        return client.product().findById(id).map(ProductResponse::from);
    }

    ProductPage list(int page, int size) {
        List<Product> rows = client.product().findMany(query -> query
                .orderBy(ProductOrder.id().asc()).skip(page * size).take(size + 1));
        return new ProductPage(rows.stream().limit(size).map(ProductResponse::from).toList(),
                page, size, rows.size() > size);
    }

    Optional<ProductResponse> replace(UUID id, ProductRequest request) {
        return client.product().updateById(id, ProductUpdate.builder()
                .name(request.name()).price(request.price()).stock(request.stock()).build()).map(ProductResponse::from);
    }

    boolean delete(UUID id) {
        return client.product().deleteById(id);
    }
}
