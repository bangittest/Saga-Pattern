package com.demo.order;

/**
 * The single event type that flows over the "saga-events" Kafka topic.
 * Every service publishes and consumes these; each filters on `type`.
 * (Copied into each service because services are independent — no shared jar.)
 */
public class SagaEvent {
    public String eventId;     // unique per event -> used by consumers to dedup (idempotency)
    public String type;        // ORDER_CREATED, PAYMENT_COMPLETED, PAYMENT_FAILED,
                               // PAYMENT_REFUNDED, INVENTORY_RESERVED, INVENTORY_FAILED
    public Long orderId;
    public String customerId;
    public String productId;
    public int quantity;
    public double amount;
    public String failAt;      // demo kill-switch carried along the saga
    public String reason;

    public SagaEvent() {}
}