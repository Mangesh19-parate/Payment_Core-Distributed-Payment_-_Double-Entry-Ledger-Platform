package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxRequestsPerMinute;
    private final boolean rateLimitingEnabled;

    public RateLimitingFilter(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.rate-limiting.max-requests-per-minute:120}") int maxRequestsPerMinute,
            @Value("${app.rate-limiting.enabled:true}") boolean rateLimitingEnabled
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.rateLimitingEnabled = rateLimitingEnabled;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!rateLimitingEnabled || !isMutatingEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(request);
        String principalId = request.getHeader("X-Principal-ID");
        String rateLimitKey = "ratelimit:" + (principalId != null && !principalId.isBlank() ? principalId.trim() : "anon") + ":" + clientIp;

        try {
            Long currentCount = redisTemplate.opsForValue().increment(rateLimitKey);
            if (currentCount != null && currentCount == 1) {
                redisTemplate.expire(rateLimitKey, Duration.ofMinutes(1));
            }

            if (currentCount != null && currentCount > maxRequestsPerMinute) {
                log.warn("Rate limit exceeded for {}: count={}/min", rateLimitKey, currentCount);
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                        "error", "TOO_MANY_REQUESTS",
                        "message", "Rate limit exceeded. Maximum " + maxRequestsPerMinute + " requests per minute allowed.",
                        "timestamp", Instant.now().toString()
                )));
                return;
            }
        } catch (Exception ex) {
            log.error("Error executing rate limiting check", ex);
            // Fail-open for rate limiter if Redis encounters hiccup, allowing business flow to proceed
        }

        filterChain.doFilter(request, response);
    }

    private boolean isMutatingEndpoint(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
