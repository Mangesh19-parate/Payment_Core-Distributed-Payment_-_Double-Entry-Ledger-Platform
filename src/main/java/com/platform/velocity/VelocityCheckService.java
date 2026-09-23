package com.platform.velocity;

import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class VelocityCheckService {

    private static final Logger log = LoggerFactory.getLogger(VelocityCheckService.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> velocityScript;
    private final Duration windowDuration;
    private final long maxAmountPaise;

    public VelocityCheckService(
            StringRedisTemplate redisTemplate,
            @Value("${app.velocity.window-seconds:3600}") long windowSeconds,
            @Value("${app.velocity.max-amount-paise:50000000}") long maxAmountPaise
    ) {
        this.redisTemplate = redisTemplate;
        this.windowDuration = Duration.ofSeconds(windowSeconds);
        this.maxAmountPaise = maxAmountPaise;

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/velocity_check.lua"));
        script.setResultType(List.class);
        this.velocityScript = script;
    }

    /**
     * Evaluates sliding window monetary sum atomically via Redis Lua script (REQ-060, REQ-061).
     * Enforces strict fail-closed semantics upon Redis failure (REQ-062).
     */
    public void checkAndRecord(UUID accountId, long amountPaise, String operationId) {
        String key = "velocity:account:" + accountId;
        long nowMs = Instant.now().toEpochMilli();
        long windowSizeMs = windowDuration.toMillis();

        try {
            List<?> result = redisTemplate.execute(
                    velocityScript,
                    Collections.singletonList(key),
                    String.valueOf(nowMs),
                    String.valueOf(windowSizeMs),
                    String.valueOf(amountPaise),
                    String.valueOf(maxAmountPaise),
                    operationId
            );

            if (result == null || result.isEmpty()) {
                log.error("Velocity check returned empty response for account {} - failing closed (REQ-062)", accountId);
                throw new BusinessException(ErrorCode.VELOCITY_CHECK_FAILED, "Velocity check failed closed");
            }

            long status = ((Number) result.get(0)).longValue();
            long currentSum = result.size() > 1 ? ((Number) result.get(1)).longValue() : 0;
            long limit = result.size() > 2 ? ((Number) result.get(2)).longValue() : maxAmountPaise;

            if (status == 0) {
                log.warn("Velocity limit exceeded for account {}: requested={}, currentSum={}, limit={}",
                        accountId, amountPaise, currentSum, limit);
                throw new BusinessException(
                        ErrorCode.VELOCITY_LIMIT_EXCEEDED,
                        String.format("Velocity limit exceeded: current window sum %d paise + requested %d paise exceeds limit of %d paise",
                                currentSum, amountPaise, limit)
                );
            }

            log.debug("Velocity check passed for account {}: new sum = {} paise", accountId, currentSum);

        } catch (BusinessException be) {
            throw be;
        } catch (Exception ex) {
            // REQ-062: Fail-closed on Redis unavailability
            log.error("Redis error during velocity check for account {} - failing closed (REQ-062)", accountId, ex);
            throw new BusinessException(
                    ErrorCode.VELOCITY_CHECK_FAILED,
                    "Velocity check service unavailable - failing closed"
            );
        }
    }
}
