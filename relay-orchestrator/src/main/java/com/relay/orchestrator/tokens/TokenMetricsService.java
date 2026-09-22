package com.relay.orchestrator.tokens;

import com.relay.orchestrator.agent.AgentRole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Persists every LLM call to the token_metrics SQLite table and exposes
 * aggregated views for the UI. Same relay.db as the code index.
 */
@Service
public class TokenMetricsService {

    private static final Logger log = LoggerFactory.getLogger(TokenMetricsService.class);

    private static final double INPUT_COST_PER_M  = 3.0;
    private static final double OUTPUT_COST_PER_M = 15.0;
    private static final double CACHE_READ_DISCOUNT = 0.1;

    private final JdbcTemplate jdbc;

    public TokenMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureSchema();
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS token_metrics (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  session_id TEXT,
                  role TEXT NOT NULL,
                  persona_name TEXT,
                  model TEXT,
                  input_tokens_before INTEGER NOT NULL DEFAULT 0,
                  input_tokens_after  INTEGER NOT NULL DEFAULT 0,
                  output_tokens_before INTEGER NOT NULL DEFAULT 0,
                  output_tokens_after  INTEGER NOT NULL DEFAULT 0,
                  cache_read_tokens  INTEGER NOT NULL DEFAULT 0,
                  cache_write_tokens INTEGER NOT NULL DEFAULT 0,
                  estimated_cost_usd REAL NOT NULL DEFAULT 0,
                  created_at TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_token_metrics_session ON token_metrics(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_token_metrics_role ON token_metrics(role)");
        log.info("token_metrics table ready");
    }

    public void record(String sessionId,
                       AgentRole role,
                       String personaName,
                       String model,
                       int inputBefore,
                       int inputAfter,
                       int outputBefore,
                       int outputAfter,
                       int cacheRead,
                       int cacheWrite) {

        double cost = estimateCost(inputAfter, outputAfter, cacheRead, cacheWrite);

        jdbc.update("""
                INSERT INTO token_metrics
                  (session_id, role, persona_name, model,
                   input_tokens_before, input_tokens_after,
                   output_tokens_before, output_tokens_after,
                   cache_read_tokens, cache_write_tokens,
                   estimated_cost_usd, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, role.name(), personaName, model,
                inputBefore, inputAfter, outputBefore, outputAfter,
                cacheRead, cacheWrite, cost, LocalDateTime.now().toString());
    }

    public List<TokenMetrics> recent(int limit) {
        return jdbc.query(
                "SELECT * FROM token_metrics ORDER BY id DESC LIMIT ?",
                new TokenMetricsRowMapper(), limit);
    }

    public int totalRecords() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM token_metrics", Integer.class);
        return n == null ? 0 : n;
    }

    public int tokensToday() {
        String todayStart = LocalDateTime.now().toLocalDate().atStartOfDay().toString();
        Integer n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(input_tokens_after + output_tokens_after), 0) "
                        + "FROM token_metrics WHERE created_at >= ?",
                Integer.class, todayStart);
        return n == null ? 0 : n;
    }

    public double costToday() {
        String todayStart = LocalDateTime.now().toLocalDate().atStartOfDay().toString();
        Double d = jdbc.queryForObject(
                "SELECT COALESCE(SUM(estimated_cost_usd), 0) "
                        + "FROM token_metrics WHERE created_at >= ?",
                Double.class, todayStart);
        return d == null ? 0.0 : d;
    }


    // Anthropic pricing multipliers (relative to base input rate):
    private static final double CACHE_WRITE_MULTIPLIER = 1.25; // 25% premium to write
    private static final double CACHE_READ_MULTIPLIER  = 0.10; // 90% discount on read

    private double estimateCost(int totalInputTokens, int outputTokens,
                                 int cacheReadTokens, int cacheWriteTokens) {
        // "inputTokens" param is the total the API processed.
        // Split it into fresh + cached portions for accurate pricing.
        int freshInputTokens = Math.max(0,
                totalInputTokens - cacheReadTokens - cacheWriteTokens);

        double freshCost = (freshInputTokens / 1_000_000.0) * INPUT_COST_PER_M;
        double cacheWriteCost = (cacheWriteTokens / 1_000_000.0)
                * INPUT_COST_PER_M * CACHE_WRITE_MULTIPLIER;
        double cacheReadCost = (cacheReadTokens / 1_000_000.0)
                * INPUT_COST_PER_M * CACHE_READ_MULTIPLIER;
        double outputCost = (outputTokens / 1_000_000.0) * OUTPUT_COST_PER_M;

        return freshCost + cacheWriteCost + cacheReadCost + outputCost;
    }

    private static class TokenMetricsRowMapper implements RowMapper<TokenMetrics> {
        @Override
        public TokenMetrics mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TokenMetrics(
                    rs.getLong("id"),
                    rs.getString("session_id"),
                    AgentRole.valueOf(rs.getString("role")),
                    rs.getString("persona_name"),
                    rs.getString("model"),
                    rs.getInt("input_tokens_before"),
                    rs.getInt("input_tokens_after"),
                    rs.getInt("output_tokens_before"),
                    rs.getInt("output_tokens_after"),
                    rs.getInt("cache_read_tokens"),
                    rs.getInt("cache_write_tokens"),
                    rs.getDouble("estimated_cost_usd"),
                    LocalDateTime.parse(rs.getString("created_at")));
        }
    }

        public long cacheReadsToday() {
        String todayStart = LocalDateTime.now().toLocalDate().atStartOfDay().toString();
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cache_read_tokens), 0) "
                        + "FROM token_metrics WHERE created_at >= ?",
                Long.class, todayStart);
        return n == null ? 0L : n;
    }

    public long cacheWritesToday() {
        String todayStart = LocalDateTime.now().toLocalDate().atStartOfDay().toString();
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cache_write_tokens), 0) "
                        + "FROM token_metrics WHERE created_at >= ?",
                Long.class, todayStart);
        return n == null ? 0L : n;
    }
}