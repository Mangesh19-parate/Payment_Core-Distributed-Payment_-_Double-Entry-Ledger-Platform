package com.platform;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.builder.SpringApplicationBuilder;
import redis.embedded.RedisServer;

import java.util.ArrayList;
import java.util.List;

/**
 * DevServerLauncher boots embedded PostgreSQL and embedded Redis,
 * configuring Spring Boot to host the full PaymentCore platform (APIs + Frontend UI) on http://localhost:8080.
 */
public class DevServerLauncher {

    public static void main(String[] args) {
        try {
            System.out.println("=================================================");
            System.out.println(" Starting PaymentCore Embedded Infrastructure... ");
            System.out.println("=================================================");

            // 1. Embedded Postgres
            System.out.println("-> Starting Embedded PostgreSQL...");
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            int pgPort = pg.getPort();
            String jdbcUrl = "jdbc:postgresql://localhost:" + pgPort + "/postgres";
            System.out.println("✓ Embedded PostgreSQL running on port " + pgPort);

            // 2. Embedded Redis
            System.out.println("-> Starting Embedded Redis...");
            int redisPort = 6370;
            RedisServer redis = RedisServer.newRedisServer().port(redisPort).build();
            try {
                redis.start();
                System.out.println("✓ Embedded Redis running on port " + redisPort);
            } catch (Exception e) {
                System.out.println("! Redis startup note: " + e.getMessage());
            }

            final EmbeddedPostgres finalPg = pg;
            final RedisServer finalRedis = redis;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down embedded infrastructure...");
                try {
                    finalRedis.stop();
                } catch (Exception ignored) {}
                try {
                    finalPg.close();
                } catch (Exception ignored) {}
            }));

            // 3. Set System Properties for highest precedence in Spring Boot
            System.setProperty("DB_URL", jdbcUrl);
            System.setProperty("DB_USERNAME", "postgres");
            System.setProperty("DB_PASSWORD", "postgres");
            System.setProperty("spring.datasource.url", jdbcUrl);
            System.setProperty("spring.datasource.username", "postgres");
            System.setProperty("spring.datasource.password", "postgres");
            System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
            System.setProperty("spring.datasource.hikari.maximum-pool-size", "20");
            System.setProperty("spring.datasource.hikari.connection-init-sql", "SET lock_timeout = '15000ms'");

            System.setProperty("REDIS_HOST", "localhost");
            System.setProperty("REDIS_PORT", String.valueOf(redisPort));
            System.setProperty("spring.data.redis.host", "localhost");
            System.setProperty("spring.data.redis.port", String.valueOf(redisPort));

            System.setProperty("OUTBOX_RELAY_ENABLED", "false");
            System.setProperty("RECONCILIATION_ENABLED", "false");
            System.setProperty("spring.kafka.listener.auto-startup", "false");

            List<String> appArgs = new ArrayList<>(List.of(args));
            appArgs.add("--spring.datasource.url=" + jdbcUrl);
            appArgs.add("--spring.datasource.username=postgres");
            appArgs.add("--spring.datasource.password=postgres");
            appArgs.add("--spring.data.redis.port=" + redisPort);
            appArgs.add("--server.port=8080");

            System.out.println("-> Booting PaymentCore Spring Boot Application on port 8080...");
            new SpringApplicationBuilder(PaymentPlatformApplication.class)
                    .run(appArgs.toArray(new String[0]));

            System.out.println("\n=================================================================");
            System.out.println("  🚀 PaymentCore Platform is LIVE and RUNNING!");
            System.out.println("  🌐 Frontend Dashboard: http://localhost:8080");
            System.out.println("  🛡️  Ledger Invariants: http://localhost:8080/api/demo/invariants");
            System.out.println("  ⚖️  Reconciliation:    http://localhost:8080/api/demo/reconciliation");
            System.out.println("  🧪 Failure Lab:        http://localhost:8080/api/demo/failure-lab/INSUFFICIENT_FUNDS");
            System.out.println("=================================================================\n");

        } catch (Exception e) {
            System.err.println("Failed to launch PaymentCore Dev Server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
