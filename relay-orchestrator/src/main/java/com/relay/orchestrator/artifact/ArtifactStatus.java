package com.relay.orchestrator.artifact;

/**
 * Lifecycle of an artifact.
 *
 * DRAFT — producer created it, awaiting human review
 * APPROVED — user approved; the corresponding executor can now run
 * REJECTED — user rejected; kept for history, terminal state
 * EXECUTED — an executor has consumed this artifact and produced output
 *
 * State transitions:
 * DRAFT → APPROVED → EXECUTED
 * DRAFT → REJECTED
 */
public enum ArtifactStatus {
    DRAFT,
    APPROVED,
    REJECTED,
    EXECUTED
}