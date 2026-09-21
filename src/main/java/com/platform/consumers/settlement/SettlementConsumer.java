package com.platform.consumers.settlement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.consumers.persistence.ProcessedEventsRepository;
import com.platform.outbox.events.EventEnvelope;
import com.platform.outbox.events.TransactionPostedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumer.class);
    private static final String CONSUMER_NAME = "settlement_consumer";

    private final ProcessedEventsRepository processedEventsRepository;
    private final ObjectMapper objectMapper;

    public SettlementConsumer(ProcessedEventsRepository processedEventsRepository, ObjectMapper objectMapper) {
        this.processedEventsRepository = processedEventsRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.transaction-posted:transactions.posted}",
            groupId = "settlement-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onTransactionPosted(String messagePayload) throws Exception {
        EventEnvelope<TransactionPostedEvent> envelope = objectMapper.readValue(
                messagePayload,
                new TypeReference<>() {}
        );

        // REQ-046: Inbox deduplication
        boolean isNewEvent = processedEventsRepository.tryRecordEvent(CONSUMER_NAME, envelope.eventId());
        if (!isNewEvent) {
            log.info("Duplicate event {} ignored by {}", envelope.eventId(), CONSUMER_NAME);
            return;
        }

        TransactionPostedEvent payload = envelope.payload();
        log.info("Settlement initiated for transaction {} (source={}, dest={}, amount={} {})",
                payload.transactionId(), payload.sourceAccountId(), payload.destinationAccountId(),
                payload.amountPaise(), payload.currency());

        // Downstream settlement side effect here (e.g. clearing / partner bank posting)
    }
}
