package com.platform.outbox;

import com.platform.BaseIntegrationTest;
import com.platform.outbox.domain.OutboxEvent;
import com.platform.outbox.persistence.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("Outbox Relay Tests (REQ-041, REQ-042, REQ-043)")
public class OutboxRelayTest extends BaseIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    @DisplayName("REQ-041, REQ-043: Relay claims pending events, publishes to Kafka, and marks PUBLISHED")
    void testRelayClaimsAndPublishesEvents() {
        UUID txnId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(txnId, "TransactionPosted", "{\"test\":\"payload\"}");
        outboxRepository.saveAll(List.of(event));

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        OutboxRelay relay = new OutboxRelay(
                outboxRepository,
                kafkaTemplate,
                50,
                30,
                5,
                "transactions.posted",
                "accounts.balance-changed"
        );

        relay.relayEvents();

        // Verify KafkaTemplate was called
        verify(kafkaTemplate, atLeastOnce()).send(eq("transactions.posted"), eq(txnId.toString()), anyString());

        // Verify event status is updated to PUBLISHED
        List<Map<String, Object>> rows = testJdbcTemplate.queryForList(
                "SELECT * FROM outbox_events WHERE aggregate_id = ?", txnId
        );
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("status")).isEqualTo("PUBLISHED");
        assertThat(rows.get(0).get("published_at")).isNotNull();
    }

    @Test
    @DisplayName("REQ-043: Failed Kafka send updates retry backoff and lease_until")
    void testFailedPublishTriggersBackoff() {
        UUID txnId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(txnId, "TransactionPosted", "{\"fail\":\"payload\"}");
        outboxRepository.saveAll(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker unreachable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedFuture);

        OutboxRelay relay = new OutboxRelay(
                outboxRepository,
                kafkaTemplate,
                50,
                30,
                5,
                "transactions.posted",
                "accounts.balance-changed"
        );

        relay.relayEvents();

        List<Map<String, Object>> rows = testJdbcTemplate.queryForList(
                "SELECT * FROM outbox_events WHERE aggregate_id = ?", txnId
        );
        assertThat(rows).isNotEmpty();
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((Integer) row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_error")).toString().contains("Kafka broker unreachable");
        assertThat(row.get("lease_until")).isNotNull();
    }
}
