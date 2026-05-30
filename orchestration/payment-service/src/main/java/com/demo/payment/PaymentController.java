package com.demo.payment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Payment Service owns the "payments" database.
 * - POST /payments        : local transaction that charges the customer (the saga step)
 * - POST /payments/{id}/refund : the COMPENSATING transaction (undo the charge)
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentRepository repo;

    public PaymentController(PaymentRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public Payment charge(@RequestBody Map<String, Object> body) {
        // simulateFailure lets the demo force this step to fail on demand.
        boolean simulateFailure = Boolean.TRUE.equals(body.get("simulateFailure"));
        if (simulateFailure) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Payment declined (simulated)");
        }
        Payment p = new Payment();
        p.setOrderId(Long.valueOf(body.get("orderId").toString()));
        p.setAmount(Double.parseDouble(body.get("amount").toString()));
        p.setStatus("COMPLETED");
        return repo.save(p);
    }

    /** Compensation: mark the payment as refunded. Idempotent-ish for the demo. */
    @PostMapping("/{orderId}/refund")
    public ResponseEntity<Payment> refund(@PathVariable Long orderId) {
        return repo.findByOrderId(orderId)
                .map(p -> {
                    p.setStatus("REFUNDED");
                    return ResponseEntity.ok(repo.save(p));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public Iterable<Payment> all() {
        return repo.findAll();
    }
}