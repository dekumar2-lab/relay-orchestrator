package com.relay.orchestrator.retrieval;

import com.relay.orchestrator.retrieval.ChunkRepository.ChunkWithVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic retrieval over the indexed codebase.
 *
 * Flow:
 * 1. Embed the query (story) with the local MiniLM model.
 * 2. Load every stored chunk from SQLite.
 * 3. Score each chunk with cosine similarity.
 * 4. Filter by similarity floor.
 * 5. Sort descending by score.
 * 6. Apply top-K and token-budget trimming.
 * 7. Return the survivors.
 *
 * Trimming happens AFTER sorting so the token budget keeps the most
 * relevant chunks and drops the least.
 *
 * Both query and stored vectors are L2-normalized (see EmbeddingService),
 * so cosine similarity reduces to a dot product. If that invariant ever
 * changes, switch to a full cosine computation.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    // Tunable defaults. Override per-call via the extended signature.
    private static final int DEFAULT_TOP_K = 5;
    private static final float DEFAULT_SIMILARITY_FLOOR = 0.20f;
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 2000;

    private final EmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;

    public RetrievalService(EmbeddingService embeddingService,
            ChunkRepository chunkRepository) {
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
    }

    /** Convenience overload using the default tuning constants. */
    public List<RetrievedChunk> retrieveForStory(String story) {
        return retrieveForStory(story, DEFAULT_TOP_K, DEFAULT_SIMILARITY_FLOOR,
                DEFAULT_MAX_CONTEXT_TOKENS);
    }

    /**
     * Retrieve top-K chunks for the story, subject to a similarity floor
     * and a maximum total token budget.
     */
    public List<RetrievedChunk> retrieveForStory(String story,
            int topK,
            float similarityFloor,
            int maxContextTokens) {

        if (story == null || story.isBlank()) {
            return List.of();
        }
        if (!embeddingService.isAvailable()) {
            log.warn("Embedding service unavailable — retrieval skipped");
            return List.of();
        }

        List<ChunkWithVector> allChunks = chunkRepository.findAll();
        if (allChunks.isEmpty()) {
            log.info("Retrieval skipped: no chunks indexed yet");
            return List.of();
        }

        float[] queryVector;
        try {
            queryVector = embeddingService.embed(story);
        } catch (Exception e) {
            log.error("Failed to embed query story: {}", e.getMessage());
            return List.of();
        }

        // Score everything above the floor
        List<RetrievedChunk> scored = new ArrayList<>();
        for (ChunkWithVector cwv : allChunks) {
            float sim = cosine(queryVector, cwv.vector());
            if (sim >= similarityFloor) {
                scored.add(new RetrievedChunk(cwv.chunk(), sim));
            }
        }

        // Sort descending: most relevant first
        scored.sort((a, b) -> Float.compare(b.similarity(), a.similarity()));

        // Apply top-K and token budget
        List<RetrievedChunk> kept = new ArrayList<>();
        int tokensUsed = 0;
        for (RetrievedChunk r : scored) {
            if (kept.size() >= topK)
                break;
            int chunkTokens = estimateTokens(r.chunk().content());
            if (tokensUsed + chunkTokens > maxContextTokens)
                break;
            kept.add(r);
            tokensUsed += chunkTokens;
        }

        log.info("Retrieval: {} candidates, {} above floor {:.2f}, kept {} (~{} tokens)",
                allChunks.size(), scored.size(), similarityFloor, kept.size(), tokensUsed);

        for (RetrievedChunk r : kept) {
            log.debug("  {:.3f}  {}", r.similarity(), r.chunk().qualifiedName());
        }

        return kept;
    }

    // ----------------------------------------------------------------
    // Math
    // ----------------------------------------------------------------

    private float cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0)
            return 0f;
        float dot = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private int estimateTokens(String text) {
        if (text == null)
            return 0;
        // ~4 chars per token for English text mixed with identifiers
        return Math.max(1, text.length() / 4);
    }
}