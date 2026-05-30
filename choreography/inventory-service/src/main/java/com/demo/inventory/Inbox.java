package com.demo.inventory;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Idempotent consumer (Inbox pattern) — see order-service for the full explanation.
 * Records handled event ids so a redelivered event never double-reserves stock.
 */
@Entity
@Table(name = "processed_events")
class ProcessedEvent {
    @Id
    String eventId;
    Instant processedAt = Instant.now();

    protected ProcessedEvent() {}
    ProcessedEvent(String eventId) { this.eventId = eventId; }
}

interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}

@Component
class Inbox {
    private final ProcessedEventRepository repo;

    Inbox(ProcessedEventRepository repo) { this.repo = repo; }

    boolean seen(String eventId) {
        return eventId != null && repo.existsById(eventId);
    }

    void record(String eventId) {
        if (eventId != null) repo.save(new ProcessedEvent(eventId));
    }
}