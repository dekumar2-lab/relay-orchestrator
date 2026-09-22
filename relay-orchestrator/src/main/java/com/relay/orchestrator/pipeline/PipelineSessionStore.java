package com.relay.orchestrator.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.orchestrator.service.ClarificationResult;
import com.relay.orchestrator.service.ClarifyingQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite persistence for pipeline sessions. One row per session.
 * Q&A history and last ClarificationResult are stored as JSON columns.
 *
 * This is the checkpointer in LangGraph4j terms — the state lives here
 * so the loop can pause and resume across app restarts.
 */
@Service
public class PipelineSessionStore {

    private static final Logger log = LoggerFactory.getLogger(PipelineSessionStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public PipelineSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureSchema();
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pipeline_sessions (
                  id TEXT PRIMARY KEY,
                  original_story TEXT NOT NULL,
                  stage TEXT NOT NULL,
                  turn_count INTEGER NOT NULL DEFAULT 0,
                  max_turns INTEGER NOT NULL DEFAULT 3,
                  qa_history_json TEXT,
                  last_result_json TEXT,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_pipeline_sessions_stage ON pipeline_sessions(stage)");
        log.info("pipeline_sessions table ready");
    }

    public void save(PipelineSession session) {
        try {
            String qaJson = mapper.writeValueAsString(session.qaHistory());
            String resultJson = session.lastResult() != null
                    ? mapper.writeValueAsString(session.lastResult())
                    : null;

            // SQLite UPSERT
            jdbc.update("""
                    INSERT INTO pipeline_sessions
                      (id, original_story, stage, turn_count, max_turns,
                       qa_history_json, last_result_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                      stage = excluded.stage,
                      turn_count = excluded.turn_count,
                      qa_history_json = excluded.qa_history_json,
                      last_result_json = excluded.last_result_json,
                      updated_at = excluded.updated_at
                    """,
                    session.id(),
                    session.originalStory(),
                    session.stage().name(),
                    session.turnCount(),
                    session.maxTurns(),
                    qaJson,
                    resultJson,
                    session.createdAt().toString(),
                    session.updatedAt().toString());
        } catch (Exception e) {
            log.error("Failed to save pipeline session {}", session.id(), e);
            throw new RuntimeException("Session save failed", e);
        }
    }

    public Optional<PipelineSession> find(String id) {
        List<PipelineSession> rows = jdbc.query(
                "SELECT * FROM pipeline_sessions WHERE id = ?",
                new SessionRowMapper(),
                id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<PipelineSession> recent(int limit) {
        return jdbc.query(
                "SELECT * FROM pipeline_sessions ORDER BY updated_at DESC LIMIT ?",
                new SessionRowMapper(),
                limit);
    }

    private class SessionRowMapper implements RowMapper<PipelineSession> {
        @Override
        public PipelineSession mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                List<AnsweredQuestion> qa = readQa(rs.getString("qa_history_json"));
                ClarificationResult result = readResult(rs.getString("last_result_json"));

                return new PipelineSession(
                        rs.getString("id"),
                        rs.getString("original_story"),
                        PipelineStage.valueOf(rs.getString("stage")),
                        rs.getInt("turn_count"),
                        rs.getInt("max_turns"),
                        qa,
                        result,
                        LocalDateTime.parse(rs.getString("created_at")),
                        LocalDateTime.parse(rs.getString("updated_at")));
            } catch (Exception e) {
                throw new SQLException("Failed to deserialize session row", e);
            }
        }
    }

    private List<AnsweredQuestion> readQa(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<List<AnsweredQuestion>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse qa_history_json, returning empty", e);
            return new ArrayList<>();
        }
    }

    private ClarificationResult readResult(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, ClarificationResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse last_result_json", e);
            return null;
        }
    }
}