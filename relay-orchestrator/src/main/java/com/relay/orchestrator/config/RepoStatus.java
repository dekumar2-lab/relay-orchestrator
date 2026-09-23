package com.relay.orchestrator.config;

public enum RepoStatus {
    NOT_INDEXED,
    INDEXED,
    STALE, // files changed since last index
    NEEDS_REINDEX, // index predates chunking, or chunks are missing
    FAILED
}