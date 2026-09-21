package com.platform.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class IdempotencyKey {

    private IdempotencyKey() {}

    /**
     * Computes the SHA-256 canonical request hash per TRD REQ-024.
     * Pattern: sourceAccountId|destinationAccountId|amount|currency
     */
    public static String computeRequestHash(UUID sourceAccountId, UUID destinationAccountId, long amount, String currency) {
        Objects.requireNonNull(sourceAccountId, "sourceAccountId must not be null");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        String canonicalString = String.format("%s|%s|%d|%s",
                sourceAccountId,
                destinationAccountId,
                amount,
                currency
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
