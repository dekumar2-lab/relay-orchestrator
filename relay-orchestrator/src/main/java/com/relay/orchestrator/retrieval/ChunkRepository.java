package com.relay.orchestrator.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SQLite persistence for code chunks and their embeddings.
 *
 * Embeddings are stored as raw float32 BLOBs (1536 bytes each for 384-dim
 * vectors). Little-endian for consistency across platforms.
 *
 * Same relay.db as the rest of the framework. One table, one index.
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
                  embedding BLOB NOT NULL,
                  created_at TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_repo ON code_chunks(repo_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_type ON code_chunks(chunk_type)");
        log.info("code_chunks table ready");
    }

    /** Store a chunk with its embedding vector. */
    public void insert(CodeChunk chunk, float[] embedding) {
        byte[] blob = encodeVector(embedding);
        jdbc.update("""
                INSERT INTO code_chunks
                  (repo_id, chunk_type, qualified_name, file_path,
                   content, embedding, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                chunk.repoId(),
                chunk.chunkType(),
                chunk.qualifiedName(),
                chunk.filePath(),
                chunk.content(),
                blob,
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

    /**
     * Load every chunk for a repo, with embeddings decoded.
     * Used by RetrievalService (Phase 1.3c) to run cosine similarity.
     */
    public List<ChunkWithVector> findAllForRepo(String repoId) {
        return jdbc.query(
                "SELECT * FROM code_chunks WHERE repo_id = ? ORDER BY id",
                new ChunkWithVectorRowMapper(), repoId);
    }

    public List<ChunkWithVector> findAll() {
        return jdbc.query(
                "SELECT * FROM code_chunks ORDER BY id",
                new ChunkWithVectorRowMapper());
    }

    // ----------------------------------------------------------------
    // Vector encoding
    // ----------------------------------------------------------------

    private static byte[] encodeVector(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v)
            buf.putFloat(f);
        return buf.array();
    }

    private static float[] decodeVector(byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return new float[0];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++)
            out[i] = buf.getFloat();
        return out;
    }

    // ----------------------------------------------------------------
    // Row mapper + return type
    // ----------------------------------------------------------------

    /** A chunk plus its decoded vector, ready for similarity math. */
    public record ChunkWithVector(
            long id,
            CodeChunk chunk,
            float[] vector) {
    }

    private static class ChunkWithVectorRowMapper implements RowMapper<ChunkWithVector> {
        @Override
        public ChunkWithVector mapRow(ResultSet rs, int rowNum) throws SQLException {
            CodeChunk chunk = new CodeChunk(
                    rs.getString("repo_id"),
                    rs.getString("chunk_type"),
                    rs.getString("qualified_name"),
                    rs.getString("file_path"),
                    rs.getString("content"));
            return new ChunkWithVector(
                    rs.getLong("id"),
                    chunk,
                    decodeVector(rs.getBytes("embedding")));
        }
    }
}