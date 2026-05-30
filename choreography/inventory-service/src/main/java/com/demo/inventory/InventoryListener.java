package com.demo.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inventory reacts only to PAYMENT_COMPLETED (payment already succeeded):
 *  - reserve stock -> enqueue INVENTORY_RESERVED  (saga succeeds)
 *  - if it can't   -> enqueue INVENTORY_FAILED     (triggers payment refund upstream)
 *
 * @Transactional: the stock change and the outbox event commit atomically.
 */
@Component
public class InventoryListener {

    private final ProductRepository products;
    private final ReservationRepository reservations;
    private final Outbox outbox;
    private final Inbox inbox;
    private final ObjectMapper json;

    public InventoryListener(ProductRepository products, ReservationRepository reservations,
                             Outbox outbox, Inbox inbox, ObjectMapper json) {
        this.products = products;
        this.reservations = reservations;
        this.outbox = outbox;
        this.inbox = inbox;
        this.json = json;
    }

    @KafkaListener(topics = "saga-events", groupId = "inventory")
    @Transactional
    public void onEvent(String message) throws Exception {
        SagaEvent e = json.readValue(message, SagaEvent.class);
        if (!"PAYMENT_COMPLETED".equals(e.type)) return;
        if (inbox.seen(e.eventId)) return;   // duplicate delivery -> skip (idempotent)

        Product product = products.findById(e.productId).orElse(null);
        boolean fail = "inventory".equals(e.failAt)
                || product == null
                || product.getStock() < e.quantity;

        if (fail) {
            String reason = product == null ? "unknown product"
                    : ("inventory".equals(e.failAt) ? "reservation failed (simulated)" : "insufficient stock");
            outbox.enqueue(out("INVENTORY_FAILED", e, reason));
            inbox.record(e.eventId);
            return;
        }

        product.setStock(product.getStock() - e.quantity);
        products.save(product);

        Reservation r = new Reservation();
        r.setOrderId(e.orderId);
        r.setProductId(e.productId);
        r.setQuantity(e.quantity);
        reservations.save(r);

        outbox.enqueue(out("INVENTORY_RESERVED", e, null));
        inbox.record(e.eventId);
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