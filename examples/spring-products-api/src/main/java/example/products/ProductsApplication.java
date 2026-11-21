package example.products;

import dev.tuprel.runtime.TuprelDatabase;
import example.products.generated.TuprelClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ProductsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductsApplication.class, args);
    }

    @Bean
    TuprelClient tuprelClient(TuprelDatabase database) {
        return new TuprelClient(database);
    }
}
