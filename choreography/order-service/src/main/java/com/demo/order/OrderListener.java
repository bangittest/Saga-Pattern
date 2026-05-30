package com.demo.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order reacts to the OUTCOME events produced by the other services:
 *  - INVENTORY_RESERVED  -> CONFIRMED
 *  - PAYMENT_FAILED / PAYMENT_REFUNDED -> CANCELLED
 *
 * Idempotent: a redelivered event is skipped via the Inbox. (Status writes are
 * naturally idempotent too, but the Inbox keeps the pattern uniform.)
 */
@Component
public class OrderListener {

    private final OrderRepository repo;
    private final Inbox inbox;
    private final ObjectMapper json;

    public OrderListener(OrderRepository repo, Inbox inbox, ObjectMapper json) {
        this.repo = repo;
        this.inbox = inbox;
        this.json = json;
    }

    @KafkaListener(topics = "saga-events", groupId = "order")
    @Transactional
    public void onEvent(String message) throws Exception {
        SagaEvent e = json.readValue(message, SagaEvent.class);
        String next = switch (e.type) {
            case "INVENTORY_RESERVED" -> "CONFIRMED";
            case "PAYMENT_FAILED", "PAYMENT_REFUNDED" -> "CANCELLED";
            default -> null;
        };
        if (next == null) return;
        if (inbox.seen(e.eventId)) return;   // duplicate delivery -> skip (idempotent)

        repo.findById(e.orderId).ifPresent(o -> {
            o.setStatus(next);
            repo.save(o);
        });
        inbox.record(e.eventId);
    }
}