package com.platform.consumers.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.consumers.persistence.ProcessedEventsRepository;
import com.platform.outbox.events.AccountBalanceChangedEvent;
import com.platform.outbox.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private static final String CONSUMER_NAME = "notification_consumer";

    private final ProcessedEventsRepository processedEventsRepository;
    private final ObjectMapper objectMapper;

    // Track latest seen version per account for out-of-order detection (REQ-044)
    private final ConcurrentMap<String, Long> lastSeenAccountVersion = new ConcurrentHashMap<>();

    public NotificationConsumer(ProcessedEventsRepository processedEventsRepository, ObjectMapper objectMapper) {
        this.processedEventsRepository = processedEventsRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.account-balance-changed:accounts.balance-changed}",
            groupId = "notification-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onAccountBalanceChanged(String messagePayload) throws Exception {
        EventEnvelope<AccountBalanceChangedEvent> envelope = objectMapper.readValue(
                messagePayload,
                new TypeReference<>() {}
        );

        // REQ-046: Inbox deduplication
        boolean isNewEvent = processedEventsRepository.tryRecordEvent(CONSUMER_NAME, envelope.eventId());
        if (!isNewEvent) {
            log.info("Duplicate balance event {} ignored by {}", envelope.eventId(), CONSUMER_NAME);
            return;
        }

        AccountBalanceChangedEvent payload = envelope.payload();
        String accountKey = payload.accountId().toString();

        // REQ-044: Version check for ordering verification
        Long previousVersion = lastSeenAccountVersion.get(accountKey);
        if (previousVersion != null && payload.accountVersion() <= previousVersion) {
            log.warn("Out-of-order or stale event detected for account {}: currentVersion={}, lastSeenVersion={}",
                    accountKey, payload.accountVersion(), previousVersion);
        } else {
            lastSeenAccountVersion.put(accountKey, payload.accountVersion());
        }

        // REQ-047: External provider notification caveat — send notification using external idempotency key if supported
        sendNotification(payload, envelope.eventId().toString());
    }

    private void sendNotification(AccountBalanceChangedEvent payload, String idempotencyKey) {
        log.info("Sending balance update notification for account {} (delta={} paise, newBalance={} paise, version={}) [providerIdemKey={}]",
                payload.accountId(), payload.deltaPaise(), payload.newBalancePaise(), payload.accountVersion(), idempotencyKey);
    }
}
