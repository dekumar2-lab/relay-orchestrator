package com.relay.orchestrator.retrieval;

/**
 * One retrieved code chunk with its cosine similarity to the query.
 *
 * Similarity is in [0, 1] where 1 = identical meaning. Chunks are ordered
 * from most to least relevant by RetrievalService.
 */
public record RetrievedChunk(
        CodeChunk chunk,
        float similarity) {
}