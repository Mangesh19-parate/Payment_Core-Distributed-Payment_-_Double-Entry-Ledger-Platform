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
        String canonical = sourceAccountId + "|" + destinationAccountId + "|" + amount + "|" + currency;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
