package com.platform.outbox;

import com.platform.outbox.domain.OutboxEvent;
import com.platform.outbox.persistence.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox Polling Relay (REQ-041, REQ-042, REQ-043).
 *
 * Guaranteed transaction isolation:
 *  1. Claims batch of events in a short DB transaction (SKIP LOCKED).
 *  2. Publishes to Kafka outside of any DB transaction.
 *  3. Marks published or schedules retries in a subsequent short DB transaction.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final String transactionPostedTopic;
    private final String accountBalanceChangedTopic;

    public OutboxRelay(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.outbox.relay.batch-size:50}") int batchSize,
            @Value("${app.outbox.relay.lease-duration-seconds:30}") int leaseDurationSeconds,
            @Value("${app.outbox.relay.max-attempts:5}") int maxAttempts,
            @Value("${app.kafka.topics.transaction-posted:transactions.posted}") String transactionPostedTopic,
            @Value("${app.kafka.topics.account-balance-changed:accounts.balance-changed}") String accountBalanceChangedTopic
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofSeconds(leaseDurationSeconds);
        this.maxAttempts = maxAttempts;
        this.transactionPostedTopic = transactionPostedTopic;
        this.accountBalanceChangedTopic = accountBalanceChangedTopic;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.poll-rate-ms:500}")
    public void relayEvents() {
        // Step 1: Claim batch in a short, dedicated DB transaction
        List<OutboxEvent> claimedEvents = outboxRepository.claimPendingEvents(batchSize, leaseDuration);
        if (claimedEvents.isEmpty()) {
            return;
        }

        log.debug("Claimed {} outbox events for publishing", claimedEvents.size());
        List<Long> publishedIds = new ArrayList<>();

        // Step 2: Publish to Kafka with NO active DB transaction (REQ-041)
        for (OutboxEvent event : claimedEvents) {
            String topic = resolveTopic(event.eventType());
            String messageKey = event.aggregateId().toString();

            try {
                kafkaTemplate.send(topic, messageKey, event.payload())
                        .get(5, TimeUnit.SECONDS);

                publishedIds.add(event.id());
                log.debug("Successfully published outbox event {} to topic {}", event.id(), topic);
            } catch (Exception ex) {
                log.error("Failed to publish outbox event {} to topic {}: {}", event.id(), topic, ex.getMessage());
                // Exponential backoff: 2^attempt seconds
                long backoffSeconds = (long) Math.min(300, Math.pow(2, event.attemptCount()));
                outboxRepository.markFailed(
                        event.id(),
                        ex.getMessage(),
                        event.attemptCount(),
                        maxAttempts,
                        Duration.ofSeconds(backoffSeconds)
                );
            }
        }

        // Step 3: Mark successfully published events in a separate short DB transaction
        if (!publishedIds.isEmpty()) {
            outboxRepository.markPublished(publishedIds);
        }
    }

    private String resolveTopic(String eventType) {
        return switch (eventType) {
            case "TransactionPosted" -> transactionPostedTopic;
            case "AccountBalanceChanged" -> accountBalanceChangedTopic;
            default -> "events." + eventType.toLowerCase();
        };
    }
}
