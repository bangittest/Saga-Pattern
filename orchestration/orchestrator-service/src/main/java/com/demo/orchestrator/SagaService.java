package com.demo.orchestrator;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ORCHESTRATION-style Saga.
 *
 * Happy path:   create order(PENDING) -> reserve inventory -> charge payment -> confirm order
 * On failure :  run the COMPENSATING action for every step that already succeeded,
 *               in reverse order, then mark the order CANCELLED.
 *
 * This single class is the "source of truth" for the distributed transaction —
 * easy to read and debug, which is the main advantage of orchestration.
 */
@Service
public class SagaService {

    private final RestClient order;
    private final RestClient payment;
    private final RestClient inventory;

    public SagaService(@Qualifier("orderClient") RestClient order,
                       @Qualifier("paymentClient") RestClient payment,
                       @Qualifier("inventoryClient") RestClient inventory) {
        this.order = order;
        this.payment = payment;
        this.inventory = inventory;
    }

    public Map<String, Object> placeOrder(OrderRequest req) {
        List<String> log = new ArrayList<>();

        // Step 1 — create the order (PENDING). If this fails, nothing to compensate.
        Map<?, ?> created = order.post().uri("/orders")
                .body(Map.of(
                        "customerId", req.customerId,
                        "productId", req.productId,
                        "quantity", req.quantity,
                        "amount", req.amount))
                .retrieve().body(Map.class);
        Long orderId = Long.valueOf(created.get("id").toString());
        log.add("order #" + orderId + " created (PENDING)");

        boolean inventoryReserved = false;
        boolean paymentCharged = false;

        try {
            // Step 2 — reserve inventory (retried on transient errors).
            withRetry(() -> inventory.post().uri("/inventory/reserve")
                    .body(Map.of(
                            "orderId", orderId,
                            "productId", req.productId,
                            "quantity", req.quantity,
                            "simulateFailure", "inventory".equals(req.failAt)))
                    .retrieve().body(Map.class));
            inventoryReserved = true;
            log.add("inventory reserved");

            // Step 3 — charge payment (retried on transient errors).
            withRetry(() -> payment.post().uri("/payments")
                    .body(Map.of(
                            "orderId", orderId,
                            "amount", req.amount,
                            "simulateFailure", "payment".equals(req.failAt)))
                    .retrieve().body(Map.class));
            paymentCharged = true;
            log.add("payment charged");

            // Demo hook: force the LAST step to fail so the refund compensation is exercised.
            if ("confirm".equals(req.failAt)) {
                throw new IllegalStateException("order confirmation failed (simulated)");
            }

            // Step 4 — confirm the order.
            withRetry(() -> order.put().uri("/orders/{id}/status?value=CONFIRMED", orderId)
                    .retrieve().toBodilessEntity());
            log.add("order CONFIRMED");

            return Map.of("orderId", orderId, "status", "CONFIRMED", "steps", log);

        } catch (Exception ex) {
            // ---- Something failed: compensate completed steps in reverse ----
            String reason = (ex instanceof RestClientResponseException r)
                    ? r.getResponseBodyAsString() : ex.getMessage();
            log.add("FAILED: " + reason);

            // Compensations are retried too — they MUST eventually succeed or the
            // system is left inconsistent (money taken but order cancelled, etc.).
            if (paymentCharged) {
                withRetry(() -> payment.post().uri("/payments/{id}/refund", orderId)
                        .retrieve().toBodilessEntity());
                log.add("COMPENSATION: payment refunded");
            }
            if (inventoryReserved) {
                withRetry(() -> inventory.post().uri("/inventory/release")
                        .body(Map.of("orderId", orderId)).retrieve().toBodilessEntity());
                log.add("COMPENSATION: inventory released");
            }
            withRetry(() -> order.put().uri("/orders/{id}/status?value=CANCELLED", orderId)
                    .retrieve().toBodilessEntity());
            log.add("order CANCELLED");

            return Map.of("orderId", orderId, "status", "CANCELLED", "steps", log);
        }
    }

    /**
     * Retry transient failures (5xx / connection errors) up to 3 times with small backoff.
     * A 4xx is a BUSINESS failure (payment declined, out of stock) — never retried; it
     * propagates so the saga compensates immediately.
     */
    private <T> T withRetry(Supplier<T> op) {
        int maxAttempts = 3;
        for (int attempt = 1; ; attempt++) {
            try {
                return op.get();
            } catch (HttpClientErrorException businessError) {
                throw businessError;                 // 4xx -> do not retry
            } catch (RestClientException transientError) {
                if (attempt >= maxAttempts) throw transientError;
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw transientError;
                }
            }
        }
    }
}