package com.demo.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Transactional Outbox.
 *
 * The dual-write problem: "save business row" and "send to Kafka" are two systems;
 * a crash between them loses or duplicates the event. Fix: write the event into an
 * `outbox` table in the SAME db transaction as the business change (atomic), then a
 * separate relay polls the table and publishes to Kafka ("at-least-once").
 */
@Entity
@Table(name = "outbox")
class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String eventType;
    Long aggregateId;
    @Column(columnDefinition = "text")
    String payload;
    boolean sent = false;
    Instant createdAt = Instant.now();
}

interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findBySentFalseOrderByIdAsc();
}

/** Enqueue an event in the caller's db transaction (no Kafka call here). */
@Component
class Outbox {
    private final OutboxRepository repo;
    private final ObjectMapper json;

    Outbox(OutboxRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    void enqueue(SagaEvent e) {
        if (e.eventId == null) e.eventId = java.util.UUID.randomUUID().toString();
        OutboxEvent row = new OutboxEvent();
        row.eventType = e.type;
        row.aggregateId = e.orderId;
        try {
            row.payload = json.writeValueAsString(e);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        repo.save(row);
    }
}

/** Polling publisher: ship unsent rows to Kafka, then mark them sent. */
@Component
class OutboxRelay {
    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;

    OutboxRelay(OutboxRepository repo, KafkaTemplate<String, String> kafka) {
        this.repo = repo;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void flush() {
        for (OutboxEvent e : repo.findBySentFalseOrderByIdAsc()) {
            String key = e.aggregateId == null ? null : e.aggregateId.toString();
            kafka.send("saga-events", key, e.payload);
            e.sent = true;   // managed entity -> UPDATE flushed on commit
        }
    }
}