package com.relay.orchestrator.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relay.orchestrator.config.MigrationSupport;
import com.relay.orchestrator.pipeline.impl.ImplementationResult;
import com.relay.orchestrator.service.ClarificationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class PipelineSessionStore extends MigrationSupport {

    private static final String TABLE = "orch_pipeline_sessions";
    private static final String DEFAULT_SERVICE = "default";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public PipelineSessionStore(JdbcTemplate jdbc) {
        super(jdbc);
        ensureSchema();
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS orch_pipeline_sessions (
                  id TEXT PRIMARY KEY,
                  service_id TEXT NOT NULL DEFAULT 'default',
                  original_story TEXT NOT NULL,
                  stage TEXT NOT NULL,
                  turn_count INTEGER NOT NULL DEFAULT 0,
                  max_turns INTEGER NOT NULL DEFAULT 3,
                  qa_history_json TEXT,
                  last_result_json TEXT,
                  implementation_json TEXT,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                )
                """);

        addColumnIfMissing(TABLE, "compile_errors", "TEXT");

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_orch_sessions_stage ON " + TABLE + "(stage)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_orch_sessions_service ON " + TABLE + "(service_id)");

        log.info("{} table ready", TABLE);
    }

    public void save(PipelineSession session) {
        try {
            String qaJson = mapper.writeValueAsString(session.qaHistory());
            String resultJson = session.lastResult() != null
                    ? mapper.writeValueAsString(session.lastResult())
                    : null;
            String implJson = session.implementationResult() != null
                    ? mapper.writeValueAsString(session.implementationResult())
                    : null;

            jdbc.update("""
                    INSERT INTO orch_pipeline_sessions
                    (id, service_id, original_story, stage, turn_count, max_turns,
                    qa_history_json, last_result_json, implementation_json, compile_errors,
                    created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                    stage = excluded.stage,
                    turn_count = excluded.turn_count,
                    qa_history_json = excluded.qa_history_json,
                    last_result_json = excluded.last_result_json,
                    implementation_json = excluded.implementation_json,
                    compile_errors = excluded.compile_errors,
                    updated_at = excluded.updated_at
                    """,
                    session.id(),
                    DEFAULT_SERVICE,
                    session.originalStory(),
                    session.stage().name(),
                    session.turnCount(),
                    session.maxTurns(),
                    qaJson,
                    resultJson,
                    implJson,
                    session.compileErrors(),
                    session.createdAt().toString(),
                    session.updatedAt().toString());
        } catch (Exception e) {
            log.error("Failed to save pipeline session {}", session.id(), e);
            throw new RuntimeException("Session save failed", e);
        }
    }

    public Optional<PipelineSession> find(String id) {
        List<PipelineSession> rows = jdbc.query(
                "SELECT * FROM " + TABLE + " WHERE id = ?",
                new SessionRowMapper(),
                id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<PipelineSession> recent(int limit) {
        return jdbc.query(
                "SELECT * FROM " + TABLE + " ORDER BY updated_at DESC LIMIT ?",
                new SessionRowMapper(),
                limit);
    }

    public void delete(String sessionId) {
        jdbc.update("DELETE FROM " + TABLE + " WHERE id = ?", sessionId);
    }

    private class SessionRowMapper implements RowMapper<PipelineSession> {
        @Override
        public PipelineSession mapRow(ResultSet rs, int rowNum) throws SQLException {
            try {
                List<AnsweredQuestion> qa = readQa(rs.getString("qa_history_json"));
                ClarificationResult result = readResult(rs.getString("last_result_json"));
                ImplementationResult impl = readImpl(rs.getString("implementation_json"));

                return new PipelineSession(
                        rs.getString("id"),
                        rs.getString("original_story"),
                        PipelineStage.valueOf(rs.getString("stage")),
                        rs.getInt("turn_count"),
                        rs.getInt("max_turns"),
                        qa,
                        result,
                        impl,
                        rs.getString("compile_errors"),
                        LocalDateTime.parse(rs.getString("created_at")),
                        LocalDateTime.parse(rs.getString("updated_at")));
            } catch (Exception e) {
                throw new SQLException("Failed to deserialize session row", e);
            }
        }
    }

    private List<AnsweredQuestion> readQa(String json) {
        if (json == null || json.isBlank())
            return new ArrayList<>();
        try {
            return mapper.readValue(json, new TypeReference<List<AnsweredQuestion>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse qa_history_json, returning empty", e);
            return new ArrayList<>();
        }
    }

    private ClarificationResult readResult(String json) {
        if (json == null || json.isBlank())
            return null;
        try {
            return mapper.readValue(json, ClarificationResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse last_result_json", e);
            return null;
        }
    }

    private ImplementationResult readImpl(String json) {
        if (json == null || json.isBlank())
            return null;
        try {
            return mapper.readValue(json, ImplementationResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse implementation_json", e);
            return null;
        }
    }
}