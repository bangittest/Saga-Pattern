package com.demo.inventory;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }

    /** Seed a couple of products so the demo has stock to reserve. */
    @Bean
    CommandLineRunner seed(ProductRepository products) {
        return args -> {
            if (products.count() == 0) {
                products.save(new Product("P1", 10));
                products.save(new Product("P2", 5));
            }
        };
    }
}