package example.products;

import java.util.List;

public record ProductPage(List<ProductResponse> items, int page, int size, boolean hasNext) {}
