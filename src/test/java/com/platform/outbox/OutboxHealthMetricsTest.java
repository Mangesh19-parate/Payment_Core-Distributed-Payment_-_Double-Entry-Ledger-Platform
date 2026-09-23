package com.platform.outbox;

import com.platform.BaseIntegrationTest;
import com.platform.outbox.domain.OutboxEvent;
import com.platform.outbox.persistence.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class OutboxHealthMetricsTest extends BaseIntegrationTest {

    @Autowired
    private OutboxHealthMetricsService outboxHealthMetricsService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("REQ-121: 4-way outbox health metrics (pending, publishing, failed, oldest age)")
    void testOutboxHealthMetrics() {
        testJdbcTemplate.update("DELETE FROM outbox_events");

        // 1. Initially empty
        var initialMetrics = outboxHealthMetricsService.getHealthMetrics();
        assertEquals(0, initialMetrics.pendingOutboxEvents());
        assertEquals(0, initialMetrics.publishingOutboxEvents());
        assertEquals(0, initialMetrics.failedOutboxEvents());
        assertEquals(0.0, initialMetrics.oldestPendingEventAgeSeconds(), 0.01);

        // 2. Insert 2 pending events
        OutboxEvent event1 = OutboxEvent.pending(UUID.randomUUID(), "TransactionPosted", "{}");
        OutboxEvent event2 = OutboxEvent.pending(UUID.randomUUID(), "AccountBalanceChanged", "{}");
        outboxRepository.saveAll(List.of(event1, event2));

        var pendingMetrics = outboxHealthMetricsService.getHealthMetrics();
        assertEquals(2, pendingMetrics.pendingOutboxEvents());
        assertEquals(0, pendingMetrics.publishingOutboxEvents());
        assertEquals(0, pendingMetrics.failedOutboxEvents());
        assertTrue(pendingMetrics.oldestPendingEventAgeSeconds() >= 0.0);

        // 3. Claim 1 event -> moves to PUBLISHING
        List<OutboxEvent> claimed = outboxRepository.claimPendingEvents(1, Duration.ofSeconds(30));
        assertEquals(1, claimed.size());

        var publishingMetrics = outboxHealthMetricsService.getHealthMetrics();
        assertEquals(1, publishingMetrics.pendingOutboxEvents());
        assertEquals(1, publishingMetrics.publishingOutboxEvents());
        assertEquals(0, publishingMetrics.failedOutboxEvents());

        // 4. Mark 1 event failed permanently
        outboxRepository.markFailed(claimed.get(0).id(), "Kafka down", 5, 5, Duration.ofSeconds(5));

        var failedMetrics = outboxHealthMetricsService.getHealthMetrics();
        assertEquals(1, failedMetrics.pendingOutboxEvents());
        assertEquals(0, failedMetrics.publishingOutboxEvents());
        assertEquals(1, failedMetrics.failedOutboxEvents());
    }
}
