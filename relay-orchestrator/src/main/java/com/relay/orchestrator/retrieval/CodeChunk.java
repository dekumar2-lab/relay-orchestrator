package com.relay.orchestrator.retrieval;

/**
 * A retrievable unit of code: either a class-level summary or a single method.
 *
 * Content is human-readable text designed for the MiniLM embedding model —
 * identifiers plus natural-language structure, not raw source code.
 *
 * The vector is NOT part of this record. ChunkRepository.insert(chunk, vector)
 * stores both together. This keeps the model immutable and the concern
 * separation clean.
 */
public record CodeChunk(
        String repoId,
        String chunkType, // "class" or "method"
        String qualifiedName, // com.foo.Bar or com.foo.Bar.home
        String filePath, // relative path within the repo
        String content // the text that will be embedded
) {
}