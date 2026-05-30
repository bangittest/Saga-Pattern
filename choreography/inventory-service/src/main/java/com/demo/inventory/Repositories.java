package com.demo.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;

interface ProductRepository extends JpaRepository<Product, String> {
}

interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByOrderId(Long orderId);
}

@RestController
@RequestMapping("/inventory")
class InventoryController {
    private final ProductRepository products;
    InventoryController(ProductRepository products) { this.products = products; }

    @GetMapping("/products")
    Iterable<Product> stock() { return products.findAll(); }
}