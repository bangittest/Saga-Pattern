package com.demo.inventory;

import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Inventory Service owns the "inventory" database (products + reservations).
 * - POST /inventory/reserve : local transaction that decrements stock (the saga step)
 * - POST /inventory/release : the COMPENSATING transaction (give the stock back)
 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final ProductRepository products;
    private final ReservationRepository reservations;

    public InventoryController(ProductRepository products, ReservationRepository reservations) {
        this.products = products;
        this.reservations = reservations;
    }

    @PostMapping("/reserve")
    @Transactional
    public Reservation reserve(@RequestBody Map<String, Object> body) {
        boolean simulateFailure = Boolean.TRUE.equals(body.get("simulateFailure"));
        if (simulateFailure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Inventory reservation failed (simulated)");
        }
        String productId = body.get("productId").toString();
        int qty = Integer.parseInt(body.get("quantity").toString());
        Long orderId = Long.valueOf(body.get("orderId").toString());

        Product product = products.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown product " + productId));

        if (product.getStock() < qty) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Insufficient stock for " + productId + " (have " + product.getStock() + ", need " + qty + ")");
        }

        product.setStock(product.getStock() - qty);
        products.save(product);

        Reservation r = new Reservation();
        r.setOrderId(orderId);
        r.setProductId(productId);
        r.setQuantity(qty);
        return reservations.save(r);
    }

    /** Compensation: return the reserved stock and drop the reservation rows. */
    @PostMapping("/release")
    @Transactional
    public Map<String, Object> release(@RequestBody Map<String, Object> body) {
        Long orderId = Long.valueOf(body.get("orderId").toString());
        var found = reservations.findByOrderId(orderId);
        for (Reservation r : found) {
            products.findById(r.getProductId()).ifPresent(p -> {
                p.setStock(p.getStock() + r.getQuantity());
                products.save(p);
            });
            reservations.delete(r);
        }
        return Map.of("orderId", orderId, "released", found.size());
    }

    @GetMapping("/products")
    public Iterable<Product> stock() {
        return products.findAll();
    }
}