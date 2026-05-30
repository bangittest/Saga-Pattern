package com.demo.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.admin.NewTopic;

@SpringBootApplication
@EnableScheduling   // drives the OutboxRelay poller
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    /** Create the shared topic on startup (order-service is the saga entry point). */
    @Bean
    NewTopic sagaEvents() {
        return TopicBuilder.name("saga-events").partitions(1).replicas(1).build();
    }

    /** Dead-letter topic: messages that fail all retries land here for inspection/replay. */
    @Bean
    NewTopic sagaEventsDlt() {
        return TopicBuilder.name("saga-events.DLT").partitions(1).replicas(1).build();
    }

    /**
     * Retry a failing listener 3 times (1s apart); when exhausted, publish the record to
     * "<topic>.DLT" instead of getting stuck reprocessing a poison message forever.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3L));
    }
}