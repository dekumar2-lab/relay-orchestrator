package com.relay.orchestrator.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SQLite persistence for code chunks.
 *
 * NOTE (Copilot-only migration):
 * Embeddings were removed. The previous version stored a float32 BLOB per
 * chunk and exposed a ChunkWithVector record. That machinery was tied to
 * the local MiniLM model, which is no longer part of the project.
 *
 * Retrieval will be re-implemented on top of Lucene BM25 in the next
 * session. This class stores only the text content and metadata.
 *
 * Because the schema changed, delete index.db on first start after this
 * change.
 */
@Repository
public class ChunkRepository {

    private static final Logger log = LoggerFactory.getLogger(ChunkRepository.class);

    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureSchema();
    }

    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS code_chunks (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  repo_id TEXT NOT NULL,
                  chunk_type TEXT NOT NULL,
                  qualified_name TEXT NOT NULL,
                  file_path TEXT,
                  content TEXT NOT NULL,
                  created_at TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_repo ON code_chunks(repo_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_type ON code_chunks(chunk_type)");
        log.info("code_chunks table ready (no-embedding schema)");
    }

    /** Store a chunk. Called by RepoIndexerService.storeChunks(). */
    public void insert(CodeChunk chunk) {
        jdbc.update("""
                INSERT INTO code_chunks
                  (repo_id, chunk_type, qualified_name, file_path, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                chunk.repoId(),
                chunk.chunkType(),
                chunk.qualifiedName(),
                chunk.filePath(),
                chunk.content(),
                LocalDateTime.now().toString());
    }

    /** Delete all chunks for a repo. Called before a fresh index. */
    public void clearRepoChunks(String repoId) {
        int deleted = jdbc.update("DELETE FROM code_chunks WHERE repo_id = ?", repoId);
        if (deleted > 0) {
            log.debug("Cleared {} chunks for repo {}", deleted, repoId);
        }
    }

    /** True if any chunks exist for the repo. Used by the UI badge check. */
    public boolean hasChunks(String repoId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_chunks WHERE repo_id = ?",
                Integer.class, repoId);
        return n != null && n > 0;
    }

    public int countChunks(String repoId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_chunks WHERE repo_id = ?",
                Integer.class, repoId);
        return n == null ? 0 : n;
    }

    public int countAll() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_chunks", Integer.class);
        return n == null ? 0 : n;
    }

    public List<CodeChunk> findAll() {
        return jdbc.query(
                "SELECT * FROM code_chunks ORDER BY id",
                new ChunkRowMapper());
    }

    public List<CodeChunk> findAllForRepo(String repoId) {
        return jdbc.query(
                "SELECT * FROM code_chunks WHERE repo_id = ? ORDER BY id",
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