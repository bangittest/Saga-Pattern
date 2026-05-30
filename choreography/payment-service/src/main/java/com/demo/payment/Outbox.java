package com.demo.payment;

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
 * Transactional Outbox — see order-service for the full explanation.
 * Event row is written in the SAME db transaction as the payment change.
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
            e.sent = true;
        }
    }
}