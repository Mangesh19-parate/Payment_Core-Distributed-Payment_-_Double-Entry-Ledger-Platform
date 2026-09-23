package com.platform.security;

import com.platform.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
public class SecurityAndRateLimitingTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("REQ-028: Mutating endpoints require Idempotency-Key header")
    void testMutatingEndpointsRequireIdempotencyKey() throws Exception {
        UUID txnId = UUID.randomUUID();

        // 1. /transfers without Idempotency-Key
        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principalId\":\"" + UUID.randomUUID() + "\",\"sourceAccountId\":\"" + UUID.randomUUID() + "\",\"destinationAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":1000,\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // 2. /approve without Idempotency-Key
        mockMvc.perform(post("/transactions/" + txnId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principalId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // 3. /reject without Idempotency-Key
        mockMvc.perform(post("/transactions/" + txnId + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principalId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // 4. /reverse without Idempotency-Key
        mockMvc.perform(post("/transfers/transactions/" + txnId + "/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
}
