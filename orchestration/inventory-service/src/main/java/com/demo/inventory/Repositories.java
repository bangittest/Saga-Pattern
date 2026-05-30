package com.demo.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface ProductRepository extends JpaRepository<Product, String> {
}

interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByOrderId(Long orderId);
}