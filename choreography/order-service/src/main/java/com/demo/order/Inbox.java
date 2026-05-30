package com.demo.order;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Idempotent consumer (Inbox pattern).
 *
 * Outbox delivery is at-least-once, so a consumer may see the same event twice
 * (Kafka redelivery, relay re-send, or a retry after a mid-handler crash). Each
 * handler records the event's id in `processed_events` IN THE SAME db transaction
 * as its business change; if the id is already present, it skips. Atomicity makes
 * "did the work" and "marked as done" succeed-or-fail together.
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

    /** true if this event was already handled -> caller should skip. */
    boolean seen(String eventId) {
        return eventId != null && repo.existsById(eventId);
    }

    void record(String eventId) {
        if (eventId != null) repo.save(new ProcessedEvent(eventId));
    }
}