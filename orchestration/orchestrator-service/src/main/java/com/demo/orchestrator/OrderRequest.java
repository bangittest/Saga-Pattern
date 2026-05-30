package com.demo.orchestrator;

/**
 * Incoming request to place an order.
 * failAt is the demo "kill switch": set it to "inventory" or "payment"
 * to force that saga step to fail and watch the compensations run.
 */
public class OrderRequest {
    public String customerId;
    public String productId;
    public int quantity;
    public double amount;
    public String failAt;   // null | "inventory" | "payment"
}