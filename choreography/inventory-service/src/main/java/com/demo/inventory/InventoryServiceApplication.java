package com.demo.inventory;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@SpringBootApplication
@EnableScheduling   // drives the OutboxRelay poller
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }

    /** Retry 3x (1s apart); on exhaustion route the poison record to "<topic>.DLT". */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3L));
    }

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