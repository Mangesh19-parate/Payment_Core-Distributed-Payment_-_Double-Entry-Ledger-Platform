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

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("REQ-028: Mutating endpoints require Idempotency-Key header on authenticated requests")
    void testMutatingEndpointsRequireIdempotencyKey() throws Exception {
        UUID userId = createTestUser("ROLE_ADMIN");
        String token = "Bearer " + jwtTokenProvider.generateToken(new AuthenticatedUser(userId, "admin@test.com", "", UserRole.ROLE_ADMIN, true));
        UUID txnId = UUID.randomUUID();

        // 1. /transfers without Idempotency-Key
        mockMvc.perform(post("/transfers")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + UUID.randomUUID() + "\",\"destinationAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":1000,\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // 2. /approve without Idempotency-Key
        mockMvc.perform(post("/transactions/" + txnId + "/approve")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // 3. /reject without Idempotency-Key
        mockMvc.perform(post("/transactions/" + txnId + "/reject")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // 4. /reverse without Idempotency-Key
        mockMvc.perform(post("/transfers/transactions/" + txnId + "/reverse")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
}
