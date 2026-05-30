package com.demo.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.*;
import java.util.Optional;

interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
}

@RestController
@RequestMapping("/payments")
class PaymentController {
    private final PaymentRepository repo;
    PaymentController(PaymentRepository repo) { this.repo = repo; }

    @GetMapping
    Iterable<Payment> all() { return repo.findAll(); }
}