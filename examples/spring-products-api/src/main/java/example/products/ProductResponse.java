package example.products;

import example.products.generated.model.Product;
import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(UUID id, String name, BigDecimal price, int stock) {
    static ProductResponse from(Product product) {
        return new ProductResponse(product.id(), product.name(), product.price(), product.stock());
    }
}
