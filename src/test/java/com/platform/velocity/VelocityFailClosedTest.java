package com.platform.velocity;

import com.platform.common.error.BusinessException;
import com.platform.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

public class VelocityFailClosedTest {

    @Test
    @DisplayName("REQ-062: On Redis unavailability, velocity check FAILS CLOSED and rejects transfer")
    void testVelocityFailsClosedOnRedisError() {
        StringRedisTemplate mockRedis = Mockito.mock(StringRedisTemplate.class);
        when(mockRedis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        VelocityCheckService service = new VelocityCheckService(mockRedis, 3600, 50_000_000L);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.checkAndRecord(UUID.randomUUID(), 100_000L, "tx-failclosed")
        );

        assertEquals(ErrorCode.VELOCITY_CHECK_FAILED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("failing closed"));
    }
}
