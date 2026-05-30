package com.demo.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment reacts to two events:
 *  - ORDER_CREATED    -> charge -> enqueue PAYMENT_COMPLETED (or PAYMENT_FAILED)
 *  - INVENTORY_FAILED -> COMPENSATION: refund -> enqueue PAYMENT_REFUNDED
 *
 * Each handler is @Transactional, so the payment change and the outbox event commit
 * atomically (transactional outbox).
 */
@Component
public class PaymentListener {

    private final PaymentRepository repo;
    private final Outbox outbox;
    private final Inbox inbox;
    private final ObjectMapper json;

    public PaymentListener(PaymentRepository repo, Outbox outbox, Inbox inbox, ObjectMapper json) {
        this.repo = repo;
        this.outbox = outbox;
        this.inbox = inbox;
        this.json = json;
    }

    @KafkaListener(topics = "saga-events", groupId = "payment")
    @Transactional
    public void onEvent(String message) throws Exception {
        SagaEvent e = json.readValue(message, SagaEvent.class);
        if (!"ORDER_CREATED".equals(e.type) && !"INVENTORY_FAILED".equals(e.type)) return;
        if (inbox.seen(e.eventId)) return;   // duplicate delivery -> skip (idempotent)

        switch (e.type) {
            case "ORDER_CREATED" -> charge(e);
            case "INVENTORY_FAILED" -> refund(e);
        }
        inbox.record(e.eventId);
    }

    private void charge(SagaEvent e) {
        // "poison" = a message that ALWAYS throws (not a business failure). It is retried
        // by the error handler and, when retries are exhausted, routed to saga-events.DLT.
        if ("poison".equals(e.failAt)) {
            throw new RuntimeException("poison message (simulated unrecoverable error)");
        }
        if ("payment".equals(e.failAt)) {
            outbox.enqueue(out("PAYMENT_FAILED", e, "payment declined (simulated)"));
            return;
        }
        Payment p = new Payment();
        p.setOrderId(e.orderId);
        p.setAmount(e.amount);
        p.setStatus("COMPLETED");
        repo.save(p);
        outbox.enqueue(out("PAYMENT_COMPLETED", e, null));
    }

    private void refund(SagaEvent e) {
        repo.findByOrderId(e.orderId).ifPresent(p -> {
            p.setStatus("REFUNDED");
            repo.save(p);
        });
        outbox.enqueue(out("PAYMENT_REFUNDED", e, "refunded after inventory failure"));
    }

    private SagaEvent out(String type, SagaEvent in, String reason) {
        SagaEvent o = new SagaEvent();
        o.type = type;
        o.orderId = in.orderId;
        o.productId = in.productId;
        o.quantity = in.quantity;
        o.amount = in.amount;
        o.failAt = in.failAt;
        o.reason = reason;
        return o;
    }
}