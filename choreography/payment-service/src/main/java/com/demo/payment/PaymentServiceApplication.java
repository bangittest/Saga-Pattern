package com.demo.payment;

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
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

    /** Retry 3x (1s apart); on exhaustion route the poison record to "<topic>.DLT". */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3L));
    }
}