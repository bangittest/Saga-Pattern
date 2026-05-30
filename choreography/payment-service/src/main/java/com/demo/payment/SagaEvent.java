package com.demo.payment;

public class SagaEvent {
    public String eventId;     // unique per event -> used by consumers to dedup (idempotency)
    public String type;
    public Long orderId;
    public String customerId;
    public String productId;
    public int quantity;
    public double amount;
    public String failAt;
    public String reason;

    public SagaEvent() {}
}