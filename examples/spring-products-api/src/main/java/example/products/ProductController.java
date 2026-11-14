package example.products;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/products")
class ProductController {
    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @PostMapping
    ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse product = products.create(request);
        return ResponseEntity.created(URI.create("/products/" + product.id())).body(product);
    }

    @GetMapping("/{id}")
    ProductResponse find(@PathVariable UUID id) {
        return products.find(id).orElseThrow(ProductController::missingProduct);
    }

    @GetMapping
    ProductPage list(@RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return products.list(page, size);
    }

    @PutMapping("/{id}")
    ProductResponse replace(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return products.replace(id, request).orElseThrow(ProductController::missingProduct);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!products.delete(id)) {
            throw missingProduct();
        }
        return ResponseEntity.noContent().build();
    }

    private static ResponseStatusException missingProduct() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found.");
    }
}
