package com.demo.order;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Order Service owns the "orders" database. No other service touches it.
 * The orchestrator drives status changes via these endpoints.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository repo;

    public OrderController(OrderRepository repo) {
        this.repo = repo;
    }

    /** Local transaction: create the order in PENDING state. */
    @PostMapping
    public Order create(@RequestBody Order order) {
        order.setStatus("PENDING");
        return repo.save(order);
    }

    /** Local transaction used by the saga (confirm) and by compensation (cancel). */
    @PutMapping("/{id}/status")
    public ResponseEntity<Order> updateStatus(@PathVariable Long id, @RequestParam String value) {
        return repo.findById(id)
                .map(o -> {
                    o.setStatus(value);
                    return ResponseEntity.ok(repo.save(o));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable Long id) {
        return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<Order> all() {
        return repo.findAll();
    }
}