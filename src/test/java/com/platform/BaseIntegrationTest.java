package com.platform;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.UUID;

@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    private static EmbeddedPostgres embeddedPostgres;
    private static redis.embedded.RedisServer redisServer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        try {
            if (embeddedPostgres == null) {
                embeddedPostgres = EmbeddedPostgres.builder()
                        .setPort(0) // dynamic port
                        .start();
            }
            int port = embeddedPostgres.getPort();
            String jdbcUrl = "jdbc:postgresql://localhost:" + port + "/postgres";
            registry.add("spring.datasource.url", () -> jdbcUrl);
            registry.add("spring.datasource.username", () -> "postgres");
            registry.add("spring.datasource.password", () -> "postgres");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> 50);
            registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '15000ms'");

            if (redisServer == null) {
                redisServer = redis.embedded.RedisServer.newRedisServer().port(6370).build();
                redisServer.start();
            }
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> redisServer.ports().get(0));
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Embedded test infrastructure", e);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    protected org.springframework.jdbc.core.JdbcTemplate testJdbcTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    protected org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    protected UUID createTestUser() {
        UUID userId = UUID.randomUUID();
        testJdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, status, created_at) VALUES (?, ?, ?, 'ACTIVE', NOW()) ON CONFLICT (id) DO NOTHING",
                userId,
                "user-" + userId + "@platform.internal",
                "$2a$10$testpasswordhash"
        );
        return userId;
    }

    @AfterAll
    public static void tearDownPostgres() throws IOException {
        // Shared embedded postgres instance
    }
}
