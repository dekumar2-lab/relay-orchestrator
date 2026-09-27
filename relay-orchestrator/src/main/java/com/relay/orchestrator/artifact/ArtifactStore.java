package com.relay.orchestrator.artifact;

import com.relay.orchestrator.config.MigrationSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ArtifactStore extends MigrationSupport {

    private static final String TABLE = "orch_artifacts";
    private static final String DEFAULT_SERVICE = "default";

    public ArtifactStore(JdbcTemplate jdbc) {
        super(jdbc);
        ensureSchema();
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS orch_artifacts (
                  id TEXT PRIMARY KEY,
                  service_id TEXT NOT NULL DEFAULT 'default',
                  session_id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  content TEXT NOT NULL,
                  status TEXT NOT NULL,
                  created_by TEXT,
                  approved_by TEXT,
                  created_at TEXT NOT NULL,
                  approved_at TEXT
                )
                """);

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_artifacts_session ON "
                + TABLE + "(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_artifacts_status ON "
                + TABLE + "(status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_artifacts_kind ON "
                + TABLE + "(kind)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_artifacts_service ON "
                + TABLE + "(service_id)");

        log.info("{} table ready", TABLE);
    }

    /** Insert a new artifact in DRAFT status. Returns the generated id. */
    public String create(String sessionId, ArtifactKind kind,
            String content, String createdBy) {
        String id = "art_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16);
        LocalDateTime now = LocalDateTime.now();

        jdbc.update("""
                INSERT INTO orch_artifacts
                  (id, service_id, session_id, kind, content, status,
                   created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                DEFAULT_SERVICE,
                sessionId,
                kind.name(),
                content,
                ArtifactStatus.DRAFT.name(),
                createdBy,
                now.toString());

        log.debug("Created {} artifact {} for session {}",
                kind, id.substring(0, 12), sessionId.substring(0, 8));

        return id;
    }

    public Optional<Artifact> find(String id) {
        List<Artifact> rows = jdbc.query(
                "SELECT * FROM " + TABLE + " WHERE id = ?",
                new ArtifactRowMapper(),
                id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Artifact> findBySession(String sessionId) {
        return jdbc.query(
                "SELECT * FROM " + TABLE
                        + " WHERE session_id = ? ORDER BY created_at ASC",
                new ArtifactRowMapper(),
                sessionId);
    }

    public List<Artifact> findBySessionAndKind(String sessionId, ArtifactKind kind) {
        return jdbc.query(
                "SELECT * FROM " + TABLE
                        + " WHERE session_id = ? AND kind = ? ORDER BY created_at ASC",
                new ArtifactRowMapper(),
                sessionId, kind.name());
    }

    /** Latest artifact of a given kind for a session. */
    public Optional<Artifact> latestForSession(String sessionId, ArtifactKind kind) {
        List<Artifact> rows = jdbc.query(
                "SELECT * FROM " + TABLE
                        + " WHERE session_id = ? AND kind = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                new ArtifactRowMapper(),
                sessionId, kind.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Transition DRAFT → APPROVED. Returns true if the transition happened. */
    public boolean approve(String id, String approverName) {
        int n = jdbc.update("""
                UPDATE orch_artifacts
                   SET status = ?, approved_by = ?, approved_at = ?
                 WHERE id = ? AND status = ?
                """,
                ArtifactStatus.APPROVED.name(),
                approverName,
                LocalDateTime.now().toString(),
                id,
                ArtifactStatus.DRAFT.name());
        return n > 0;
    }

    /** Transition DRAFT → REJECTED. Returns true if the transition happened. */
    public boolean reject(String id) {
        int n = jdbc.update("""
                UPDATE orch_artifacts
                   SET status = ?
                 WHERE id = ? AND status = ?
                """,
                ArtifactStatus.REJECTED.name(),
                id,
                ArtifactStatus.DRAFT.name());
        return n > 0;
    }

    /** Transition APPROVED → EXECUTED. Called after an executor finishes. */
    public boolean markExecuted(String id) {
        int n = jdbc.update("""
                UPDATE orch_artifacts
                   SET status = ?
                 WHERE id = ? AND status = ?
                """,
                ArtifactStatus.EXECUTED.name(),
                id,
                ArtifactStatus.APPROVED.name());
        return n > 0;
    }

    private static class ArtifactRowMapper implements RowMapper<Artifact> {
        @Override
        public Artifact mapRow(ResultSet rs, int rowNum) throws SQLException {
            String approvedAt = rs.getString("approved_at");
            return new Artifact(
                    rs.getString("id"),
                    rs.getString("session_id"),
                    ArtifactKind.valueOf(rs.getString("kind")),
                    rs.getString("content"),
                    ArtifactStatus.valueOf(rs.getString("status")),
                    rs.getString("created_by"),
                    rs.getString("approved_by"),
                    LocalDateTime.parse(rs.getString("created_at")),
                    approvedAt == null ? null : LocalDateTime.parse(approvedAt));
        }
    }
}