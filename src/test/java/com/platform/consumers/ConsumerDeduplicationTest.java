package com.platform.consumers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.BaseIntegrationTest;
import com.platform.consumers.notification.NotificationConsumer;
import com.platform.consumers.persistence.ProcessedEventsRepository;
import com.platform.consumers.settlement.SettlementConsumer;
import com.platform.outbox.events.AccountBalanceChangedEvent;
import com.platform.outbox.events.EventEnvelope;
import com.platform.outbox.events.TransactionPostedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Consumer Inbox Deduplication Tests (REQ-044, REQ-046)")
public class ConsumerDeduplicationTest extends BaseIntegrationTest {

    @Autowired
    private SettlementConsumer settlementConsumer;

    @Autowired
    private NotificationConsumer notificationConsumer;

    @Autowired
    private ProcessedEventsRepository processedEventsRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("REQ-046: Settlement consumer deduplicates identical events via processed_events table")
    void testSettlementConsumerInboxDeduplication() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();

        TransactionPostedEvent payload = new TransactionPostedEvent(
                txnId, UUID.randomUUID(), srcId, dstId, 50_000L, "INR", "TRANSFER", Instant.now()
        );
        EventEnvelope<TransactionPostedEvent> envelope = new EventEnvelope<>(
                eventId, "TransactionPosted", 1, Instant.now(), txnId, payload
        );

        String jsonMessage = objectMapper.writeValueAsString(envelope);

        // First delivery: should process and record in processed_events
        settlementConsumer.onTransactionPosted(jsonMessage);
        assertThat(processedEventsRepository.isEventProcessed("settlement_consumer", eventId)).isTrue();

        // Count processed rows
        Integer countAfterFirst = testJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer_name = 'settlement_consumer' AND event_id = ?",
                Integer.class, eventId
        );
        assertThat(countAfterFirst).isEqualTo(1);

        // Redelivery / duplicate delivery: should be ignored idempotently
        settlementConsumer.onTransactionPosted(jsonMessage);

        Integer countAfterSecond = testJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer_name = 'settlement_consumer' AND event_id = ?",
                Integer.class, eventId
        );
        assertThat(countAfterSecond).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-044, REQ-046: Notification consumer deduplicates and tracks account versions")
    void testNotificationConsumerDeduplicationAndVersioning() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();

        AccountBalanceChangedEvent payload = new AccountBalanceChangedEvent(
                accountId, txnId, -25_000L, 75_000L, 2L, "INR", Instant.now()
        );
        EventEnvelope<AccountBalanceChangedEvent> envelope = new EventEnvelope<>(
                eventId, "AccountBalanceChanged", 1, Instant.now(), accountId, payload
        );

        String jsonMessage = objectMapper.writeValueAsString(envelope);

        notificationConsumer.onAccountBalanceChanged(jsonMessage);
        assertThat(processedEventsRepository.isEventProcessed("notification_consumer", eventId)).isTrue();

        // Duplicate delivery
        notificationConsumer.onAccountBalanceChanged(jsonMessage);
        Integer count = testJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE consumer_name = 'notification_consumer' AND event_id = ?",
                Integer.class, eventId
        );
        assertThat(count).isEqualTo(1);
    }
}
