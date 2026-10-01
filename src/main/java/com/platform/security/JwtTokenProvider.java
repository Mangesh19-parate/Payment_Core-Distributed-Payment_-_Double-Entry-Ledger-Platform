package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final byte[] secretKeyBytes;
    private final long validityInSeconds;
    private final ObjectMapper objectMapper;

    public JwtTokenProvider(
            @Value("${security.jwt.secret:default-payment-core-super-secret-jwt-signing-key-32bytes-min}") String secret,
            @Value("${security.jwt.validity-seconds:86400}") long validityInSeconds,
            ObjectMapper objectMapper
    ) {
        this.secretKeyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.validityInSeconds = validityInSeconds;
        this.objectMapper = objectMapper;
    }

    public String generateToken(AuthenticatedUser user) {
        try {
            long now = Instant.now().getEpochSecond();
            long exp = now + validityInSeconds;

            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> claims = Map.of(
                    "sub", user.id().toString(),
                    "email", user.email(),
                    "role", user.role().name(),
                    "iat", now,
                    "exp", exp
            );

            String headerEncoded = base64UrlEncode(objectMapper.writeValueAsBytes(header));
            String claimsEncoded = base64UrlEncode(objectMapper.writeValueAsBytes(claims));
            String signatureInput = headerEncoded + "." + claimsEncoded;
            String signature = sign(signatureInput);

            return signatureInput + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }

    public boolean validateToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;

            String signatureInput = parts[0] + "." + parts[1];
            String expectedSignature = sign(signatureInput);

            if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
                return false;
            }

            byte[] claimsBytes = Base64.getUrlDecoder().decode(parts[1]);
            Map<?, ?> claims = objectMapper.readValue(claimsBytes, Map.class);
            long exp = ((Number) claims.get("exp")).longValue();
            return Instant.now().getEpochSecond() < exp;
        } catch (Exception e) {
            return false;
        }
    }

    public AuthenticatedUser parseUser(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] claimsBytes = Base64.getUrlDecoder().decode(parts[1]);
            Map<?, ?> claims = objectMapper.readValue(claimsBytes, Map.class);

            UUID userId = UUID.fromString((String) claims.get("sub"));
            String email = (String) claims.get("email");
            UserRole role = UserRole.fromString((String) claims.get("role"));

            return new AuthenticatedUser(userId, email, "", role, true);
        } catch (Exception e) {
            throw new RuntimeException("Invalid JWT claims", e);
        }
    }

    private String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKeyBytes, "HmacSHA256"));
        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(rawHmac);
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
