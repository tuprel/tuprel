package fixture;

import consumer.generated.TuprelClient;
import consumer.generated.create.ProductCreate;
import java.util.Objects;

public final class Consumer {
    private Consumer() {}

    public static void main(String[] arguments) {
        ProductCreate product = ProductCreate.builder().id(1).name("Keyboard").build();
        if (product.id().value() != 1 || !product.name().value().equals("Keyboard")) {
            throw new IllegalStateException("Unexpected generated model");
        }
        Objects.requireNonNull(TuprelClient.class);
        System.out.println("Generated Java 17 consumer passed on Java " + Runtime.version().feature());
    }
}
