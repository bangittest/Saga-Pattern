package com.demo.order;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Entry point. Saves the order PENDING and enqueues ORDER_CREATED into the OUTBOX —
 * both in one db transaction (atomic). The OutboxRelay later ships it to Kafka.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository repo;
    private final Outbox outbox;

    public OrderController(OrderRepository repo, Outbox outbox) {
        this.repo = repo;
        this.outbox = outbox;
    }

    @PostMapping
    @Transactional
    public Order create(@RequestBody Order order) {
        order.setStatus("PENDING");
        Order saved = repo.save(order);

        SagaEvent e = new SagaEvent();
        e.type = "ORDER_CREATED";
        e.orderId = saved.getId();
        e.customerId = saved.getCustomerId();
        e.productId = saved.getProductId();
        e.quantity = saved.getQuantity();
        e.amount = saved.getAmount();
        e.failAt = saved.getFailAt();
        outbox.enqueue(e);   // same transaction as the order save

        return saved;
    }

    @GetMapping
    public List<Order> all() {
        return repo.findAll();
    }
}