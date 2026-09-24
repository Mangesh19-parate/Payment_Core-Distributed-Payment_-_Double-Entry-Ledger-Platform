package com.platform.fraud.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class FraudRingDetectionService {

    private static final UUID SYSTEM_CASH_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private final JdbcTemplate jdbcTemplate;

    public FraudRingDetectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private record TxEdge(
            UUID transactionId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            long amount,
            Instant createdAt
    ) {}

    @Transactional(readOnly = true)
    public FraudRingReport analyzeNetwork(int lookbackHours) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(lookbackHours > 0 ? lookbackHours : 168)); // default 7 days

        String sql = """
            SELECT id, source_account_id, destination_account_id, amount, created_at
            FROM transactions
            WHERE status = 'POSTED'
              AND source_account_id != ?
              AND destination_account_id != ?
              AND created_at >= ?
            ORDER BY created_at ASC
            """;

        List<TxEdge> edges = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new TxEdge(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_account_id", UUID.class),
                        rs.getObject("destination_account_id", UUID.class),
                        rs.getLong("amount"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                SYSTEM_CASH_ACCOUNT_ID,
                SYSTEM_CASH_ACCOUNT_ID,
                Timestamp.from(cutoff)
        );

        Set<UUID> allAccounts = new HashSet<>();
        Map<UUID, List<TxEdge>> adjacency = new HashMap<>();
        Map<UUID, List<TxEdge>> inbound = new HashMap<>();
        Map<UUID, List<TxEdge>> outbound = new HashMap<>();

        for (TxEdge edge : edges) {
            allAccounts.add(edge.sourceAccountId());
            allAccounts.add(edge.destinationAccountId());
            adjacency.computeIfAbsent(edge.sourceAccountId(), k -> new ArrayList<>()).add(edge);
            outbound.computeIfAbsent(edge.sourceAccountId(), k -> new ArrayList<>()).add(edge);
            inbound.computeIfAbsent(edge.destinationAccountId(), k -> new ArrayList<>()).add(edge);
        }

        List<CircularFlow> circularRings = detectCircularFlows(adjacency);
        List<MuleAccount> mules = detectMules(inbound, outbound);

        return new FraudRingReport(
                edges.size(),
                allAccounts.size(),
                circularRings,
                mules,
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public AccountRiskProfile evaluateAccountRisk(UUID accountId) {
        FraudRingReport report = analyzeNetwork(168);

        boolean inRing = report.detectedCircularRings().stream()
                .anyMatch(ring -> ring.accountSequence().contains(accountId));

        boolean isMule = report.suspectedMules().stream()
                .anyMatch(mule -> mule.accountId().equals(accountId));

        String inSql = "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE destination_account_id = ? AND status = 'POSTED'";
        String outSql = "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE source_account_id = ? AND status = 'POSTED'";

        Long totalIn = jdbcTemplate.queryForObject(inSql, Long.class, accountId);
        Long totalOut = jdbcTemplate.queryForObject(outSql, Long.class, accountId);
        long inAmount = totalIn != null ? totalIn : 0L;
        long outAmount = totalOut != null ? totalOut : 0L;

        double retentionRatio = inAmount > 0 ? (double) (inAmount - outAmount) / inAmount : 0.0;
        List<String> riskFactors = new ArrayList<>();
        int score = 0;

        if (inRing) {
            score += 50;
            riskFactors.add("PARTICIPANT_IN_CIRCULAR_FRAUD_RING");
        }
        if (isMule) {
            score += 40;
            riskFactors.add("SUSPECTED_RAPID_PASS_THROUGH_MULE");
        }
        if (inAmount > 0 && retentionRatio < 0.05 && outAmount >= inAmount * 0.95) {
            score += 20;
            riskFactors.add("EXTREME_LOW_BALANCE_RETENTION");
        }

        score = Math.min(100, score);
        String riskLevel = "LOW";
        if (score >= 80) {
            riskLevel = "CRITICAL";
        } else if (score >= 50) {
            riskLevel = "HIGH";
        } else if (score >= 25) {
            riskLevel = "MEDIUM";
        }

        return new AccountRiskProfile(
                accountId,
                riskLevel,
                score,
                inRing,
                isMule,
                inAmount,
                outAmount,
                retentionRatio,
                riskFactors,
                Instant.now()
        );
    }

    private List<CircularFlow> detectCircularFlows(Map<UUID, List<TxEdge>> adjacency) {
        List<CircularFlow> rings = new ArrayList<>();
        Set<String> seenCanonicalCycles = new HashSet<>();

        for (UUID startNode : adjacency.keySet()) {
            List<UUID> path = new ArrayList<>();
            List<TxEdge> edgePath = new ArrayList<>();
            Set<UUID> onStack = new HashSet<>();
            dfsFindCycles(startNode, startNode, adjacency, path, edgePath, onStack, seenCanonicalCycles, rings, 0, 6);
        }

        return rings;
    }

    private void dfsFindCycles(
            UUID current,
            UUID origin,
            Map<UUID, List<TxEdge>> adjacency,
            List<UUID> path,
            List<TxEdge> edgePath,
            Set<UUID> onStack,
            Set<String> seenCycles,
            List<CircularFlow> rings,
            int depth,
            int maxDepth
    ) {
        if (depth > maxDepth) {
            return;
        }

        path.add(current);
        onStack.add(current);

        List<TxEdge> neighbors = adjacency.getOrDefault(current, Collections.emptyList());
        for (TxEdge edge : neighbors) {
            UUID next = edge.destinationAccountId();
            if (next.equals(origin) && path.size() >= 3) {
                // Cycle closed back to start
                List<UUID> fullCycle = new ArrayList<>(path);
                fullCycle.add(origin);
                String canonicalKey = buildCanonicalCycleKey(path);
                if (seenCycles.add(canonicalKey)) {
                    long totalAmount = edgePath.stream().mapToLong(TxEdge::amount).sum() + edge.amount();
                    Instant firstTime = edgePath.isEmpty() ? edge.createdAt() : edgePath.get(0).createdAt();
                    Instant lastTime = edge.createdAt();

                    rings.add(new CircularFlow(
                            "RING-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                            path.size(),
                            fullCycle,
                            totalAmount,
                            firstTime,
                            lastTime
                    ));
                }
            } else if (!onStack.contains(next) && depth < maxDepth) {
                edgePath.add(edge);
                dfsFindCycles(next, origin, adjacency, path, edgePath, onStack, seenCycles, rings, depth + 1, maxDepth);
                edgePath.remove(edgePath.size() - 1);
            }
        }

        path.remove(path.size() - 1);
        onStack.remove(current);
    }

    private String buildCanonicalCycleKey(List<UUID> cycleNodes) {
        // Find smallest UUID index to rotate canonically
        int minIdx = 0;
        UUID minVal = cycleNodes.get(0);
        for (int i = 1; i < cycleNodes.size(); i++) {
            if (cycleNodes.get(i).compareTo(minVal) < 0) {
                minVal = cycleNodes.get(i);
                minIdx = i;
            }
        }

        StringBuilder sb = new StringBuilder();
        int n = cycleNodes.size();
        for (int i = 0; i < n; i++) {
            sb.append(cycleNodes.get((minIdx + i) % n)).append("->");
        }
        return sb.toString();
    }

    private List<MuleAccount> detectMules(Map<UUID, List<TxEdge>> inbound, Map<UUID, List<TxEdge>> outbound) {
        List<MuleAccount> mules = new ArrayList<>();
        Set<UUID> sharedAccounts = new HashSet<>(inbound.keySet());
        sharedAccounts.retainAll(outbound.keySet());

        for (UUID accountId : sharedAccounts) {
            List<TxEdge> inList = inbound.get(accountId);
            List<TxEdge> outList = outbound.get(accountId);

            for (TxEdge inEdge : inList) {
                for (TxEdge outEdge : outList) {
                    if (!outEdge.createdAt().isBefore(inEdge.createdAt())) {
                        long dwellSeconds = Duration.between(inEdge.createdAt(), outEdge.createdAt()).toSeconds();
                        // Dwell window <= 2 hours (7200s) and forwarded amount >= 80% of inbound
                        if (dwellSeconds <= 7200 && outEdge.amount() >= (long) (inEdge.amount() * 0.8)) {
                            double ratio = (double) outEdge.amount() / inEdge.amount();
                            mules.add(new MuleAccount(
                                    accountId,
                                    inEdge.sourceAccountId(),
                                    outEdge.destinationAccountId(),
                                    inEdge.amount(),
                                    outEdge.amount(),
                                    dwellSeconds,
                                    ratio,
                                    Instant.now()
                            ));
                        }
                    }
                }
            }
        }

        return mules;
    }
}
