package com.platform.security;

import com.platform.BaseIntegrationTest;
import com.platform.account.application.AccountApplicationService;
import com.platform.account.domain.Account;
import com.platform.transfer.api.TransferRequest;
import com.platform.transfer.application.TransferApplicationService;
import com.platform.transfer.domain.TransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
public class SecurityAuthorizationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AccountApplicationService accountApplicationService;

    @Autowired
    private TransferApplicationService transferApplicationService;

    private UUID userAId;
    private UUID userBId;
    private UUID checkerId;
    private UUID approverId;

    private String userAToken;
    private String userBToken;
    private String checkerToken;
    private String approverToken;

    private Account userAAccount;
    private Account userBAccount;

    @BeforeEach
    void setUp() {
        userAId = createTestUser("ROLE_CUSTOMER");
        userBId = createTestUser("ROLE_CUSTOMER");
        checkerId = createTestUser("ROLE_CHECKER");
        approverId = createTestUser("ROLE_REVERSAL_APPROVER");

        userAToken = "Bearer " + jwtTokenProvider.generateToken(new AuthenticatedUser(userAId, "userA@test.com", "", UserRole.ROLE_CUSTOMER, true));
        userBToken = "Bearer " + jwtTokenProvider.generateToken(new AuthenticatedUser(userBId, "userB@test.com", "", UserRole.ROLE_CUSTOMER, true));
        checkerToken = "Bearer " + jwtTokenProvider.generateToken(new AuthenticatedUser(checkerId, "checker@test.com", "", UserRole.ROLE_CHECKER, true));
        approverToken = "Bearer " + jwtTokenProvider.generateToken(new AuthenticatedUser(approverId, "approver@test.com", "", UserRole.ROLE_REVERSAL_APPROVER, true));

        userAAccount = accountApplicationService.createAccount(userAId, "INR");
        userBAccount = accountApplicationService.createAccount(userBId, "INR");

        accountApplicationService.fundAccount(userAId, "fund-" + UUID.randomUUID(), userAAccount.id(), 100_000L);
    }

    @Test
    @DisplayName("Unauthenticated request to transfers returns 401 Unauthorized")
    void unauthenticatedRequest_returns401() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "idemp-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sourceAccountId": "%s",
                                    "destinationAccountId": "%s",
                                    "amount": 1000,
                                    "currency": "INR"
                                }
                                """.formatted(userAAccount.id(), userBAccount.id())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("User B attempting to transfer from User A's account returns 403 Forbidden")
    void accountOwnershipViolation_returns403() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Authorization", userBToken)
                        .header("Idempotency-Key", "idemp-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sourceAccountId": "%s",
                                    "destinationAccountId": "%s",
                                    "amount": 1000,
                                    "currency": "INR"
                                }
                                """.formatted(userAAccount.id(), userBAccount.id())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Spoofed principalId in JSON body is ignored in favor of the JWT authenticated user")
    void spoofedPrincipalIdInJson_usesJwtIdentity() throws Exception {
        String key = "idemp-" + UUID.randomUUID();
        mockMvc.perform(post("/transfers")
                        .header("Authorization", userAToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "principalId": "%s",
                                    "sourceAccountId": "%s",
                                    "destinationAccountId": "%s",
                                    "amount": 1000,
                                    "currency": "INR"
                                }
                                """.formatted(userBId, userAAccount.id(), userBAccount.id())))
                .andExpect(status().isCreated());

        var txn = transferApplicationService.getTransaction(
                testJdbcTemplate.queryForObject("SELECT id FROM transactions WHERE idempotency_key = ?", UUID.class, key)
        ).orElseThrow();

        // Transaction principalId must be User A (from JWT), NOT spoofed User B
        assertEquals(userAId, txn.principalId());
    }

    @Test
    @DisplayName("Customer role attempting to approve a transaction returns 403 Forbidden")
    void customerRole_cannotApproveTransaction() throws Exception {
        mockMvc.perform(post("/transactions/" + UUID.randomUUID() + "/approve")
                        .header("Authorization", userAToken)
                        .header("Idempotency-Key", "idemp-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Maker cannot approve their own high-value transaction (Maker-Checker violation)")
    void makerCannotApproveOwnTransaction() throws Exception {
        // Create high value transfer requiring approval (> 50,000 INR = 5,000,000 paise)
        accountApplicationService.fundAccount(userAId, "fund-large-" + UUID.randomUUID(), userAAccount.id(), 10_000_000L);
        TransferResult result = transferApplicationService.transfer(
                userAId,
                "large-tx-" + UUID.randomUUID(),
                userAAccount.id(),
                userBAccount.id(),
                6_000_000L,
                "INR"
        );

        assertTrue(result instanceof TransferResult.AwaitingApproval);
        UUID transactionId = ((TransferResult.AwaitingApproval) result).transactionId();

        // If User A obtains a checker token or calls approve on their own txn
        String userACheckerToken = "Bearer " + jwtTokenProvider.generateToken(new AuthenticatedUser(userAId, "userA@test.com", "", UserRole.ROLE_CHECKER, true));

        mockMvc.perform(post("/transactions/" + transactionId + "/approve")
                        .header("Authorization", userACheckerToken)
                        .header("Idempotency-Key", "approve-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Non-reversal approver role attempting to reverse transaction returns 403 Forbidden")
    void nonApproverReversal_returns403() throws Exception {
        mockMvc.perform(post("/transfers/transactions/" + UUID.randomUUID() + "/reverse")
                        .header("Authorization", userAToken)
                        .header("Idempotency-Key", "rev-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "reason": "Unauthorized reversal"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Login with valid credentials returns a valid JWT token")
    void authLogin_validCredentials_returnsToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "admin@platform.internal",
                                    "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));
    }
}
