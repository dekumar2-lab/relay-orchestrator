package com.relay.orchestrator.retrieval;

import com.relay.orchestrator.config.MigrationSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ChunkRepository extends MigrationSupport {

    private static final String TABLE = "idx_code_chunks";
    private static final String DEFAULT_SERVICE = "default";

    public ChunkRepository(JdbcTemplate jdbc) {
        super(jdbc);
        ensureSchema();
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS idx_code_chunks (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  service_id TEXT NOT NULL DEFAULT 'default',
                  repo_id TEXT NOT NULL,
                  chunk_type TEXT NOT NULL,
                  qualified_name TEXT NOT NULL,
                  file_path TEXT,
                  content TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_repo ON " + TABLE + "(repo_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_type ON " + TABLE + "(chunk_type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_service ON " + TABLE + "(service_id)");
        log.info("{} table ready", TABLE);
    }

    public void insert(CodeChunk chunk) {
        jdbc.update("""
                INSERT INTO idx_code_chunks
                  (service_id, repo_id, chunk_type, qualified_name, file_path, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                DEFAULT_SERVICE,
                chunk.repoId(),
                chunk.chunkType(),
                chunk.qualifiedName(),
                chunk.filePath(),
                chunk.content(),
                LocalDateTime.now().toString());
    }

    public void clearRepoChunks(String repoId) {
        int deleted = jdbc.update("DELETE FROM " + TABLE + " WHERE repo_id = ?", repoId);
        if (deleted > 0) {
            log.debug("Cleared {} chunks for repo {}", deleted, repoId);
        }
    }

    public boolean hasChunks(String repoId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE repo_id = ?",
                Integer.class, repoId);
        return n != null && n > 0;
    }

    public int countChunks(String repoId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE repo_id = ?",
                Integer.class, repoId);
        return n == null ? 0 : n;
    }

    public int countAll() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE, Integer.class);
        return n == null ? 0 : n;
    }

    public List<CodeChunk> findAll() {
        return jdbc.query(
                "SELECT * FROM " + TABLE + " ORDER BY id",
                new ChunkRowMapper());
    }

    public List<CodeChunk> findAllForRepo(String repoId) {
        return jdbc.query(
                "SELECT * FROM " + TABLE + " WHERE repo_id = ? ORDER BY id",
                new ChunkRowMapper(), repoId);
    }

    private static class ChunkRowMapper implements RowMapper<CodeChunk> {
        @Override
        public CodeChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CodeChunk(
                    rs.getString("repo_id"),
                    rs.getString("chunk_type"),
                    rs.getString("qualified_name"),
                    rs.getString("file_path"),
                    rs.getString("content"));
        }
    }
}