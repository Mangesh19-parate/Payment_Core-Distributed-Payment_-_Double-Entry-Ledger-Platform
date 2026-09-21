package com.platform.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.outbox.domain.OutboxEvent;
import com.platform.outbox.domain.OutboxEventStatus;
import com.platform.outbox.events.AccountBalanceChangedEvent;
import com.platform.outbox.events.EventEnvelope;
import com.platform.outbox.events.TransactionPostedEvent;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Outbox Atomicity Tests (REQ-040, REQ-044, REQ-045)")
public class OutboxAtomicityTest extends BaseIntegrationTest {

    @Autowired
    private TransferApplicationService transferApplicationService;

    @Autowired
    private AccountApplicationService accountApplicationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("REQ-040: Outbox events committed atomically with successful transfer")
    void testOutboxEventsCommittedAtomicallyWithTransfer() throws Exception {
        UUID principalId = createTestUser();
        Account src = accountApplicationService.createAccount(principalId, "INR");
        Account dst = accountApplicationService.createAccount(principalId, "INR");

        accountApplicationService.fundAccount(src.id(), 100_000L, UUID.randomUUID().toString());

        // Count existing outbox events before transfer
        Integer initialCount = testJdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
        int baseCount = initialCount != null ? initialCount : 0;

        String idemKey = UUID.randomUUID().toString();
        TransferResult result = transferApplicationService.transfer(
                principalId, idemKey, src.id(), dst.id(), 40_000L, "INR"
        );

        assertThat(result).isInstanceOf(TransferResult.Posted.class);
        TransferResult.Posted posted = (TransferResult.Posted) result;

        // Query outbox events for this transaction
        List<Map<String, Object>> events = testJdbcTemplate.queryForList(
                "SELECT * FROM outbox_events WHERE aggregate_id = ? OR payload::text LIKE ? ORDER BY id",
                posted.transactionId(), "%" + posted.transactionId() + "%"
        );

        // Exactly 3 outbox events: 1 TransactionPosted + 2 AccountBalanceChanged (REQ-040)
        assertThat(events).hasSize(3);

        // Verify TransactionPosted event
        Map<String, Object> txnPostedRow = events.stream()
                .filter(e -> "TransactionPosted".equals(e.get("event_type")))
                .findFirst()
                .orElseThrow();
        assertThat(txnPostedRow.get("status")).isEqualTo("PENDING");
        assertThat(txnPostedRow.get("attempt_count")).isEqualTo(0);

        EventEnvelope<TransactionPostedEvent> txnEnvelope = objectMapper.readValue(
                (String) txnPostedRow.get("payload"),
                new TypeReference<>() {}
        );
        assertThat(txnEnvelope.eventType()).isEqualTo("TransactionPosted");
        assertThat(txnEnvelope.payload().transactionId()).isEqualTo(posted.transactionId());
        assertThat(txnEnvelope.payload().amountPaise()).isEqualTo(40_000L);

        // Verify AccountBalanceChanged events carry updated versions (REQ-044)
        List<Map<String, Object>> balanceRows = events.stream()
                .filter(e -> "AccountBalanceChanged".equals(e.get("event_type")))
                .toList();
        assertThat(balanceRows).hasSize(2);

        for (Map<String, Object> row : balanceRows) {
            assertThat(row.get("status")).isEqualTo("PENDING");
            EventEnvelope<AccountBalanceChangedEvent> balanceEnvelope = objectMapper.readValue(
                    (String) row.get("payload"),
                    new TypeReference<>() {}
            );
            assertThat(balanceEnvelope.payload().transactionId()).isEqualTo(posted.transactionId());
            assertThat(balanceEnvelope.payload().accountVersion()).isGreaterThan(0L);
        }
    }

    @Test
    @DisplayName("REQ-040: No outbox events committed on business-final failure (insufficient funds)")
    void testNoOutboxEventsCommittedOnBusinessFailure() {
        UUID principalId = createTestUser();
        Account src = accountApplicationService.createAccount(principalId, "INR");
        Account dst = accountApplicationService.createAccount(principalId, "INR");

        accountApplicationService.fundAccount(src.id(), 1_000L, UUID.randomUUID().toString());

        Integer countBefore = testJdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);

        String idemKey = UUID.randomUUID().toString();
        TransferResult result = transferApplicationService.transfer(
                principalId, idemKey, src.id(), dst.id(), 500_000L, "INR"
        );

        assertThat(result).isInstanceOf(TransferResult.BusinessFailure.class);

        Integer countAfter = testJdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
        assertThat(countAfter).isEqualTo(countBefore);
    }
}
