package com.relay.orchestrator.artifact;

import com.relay.orchestrator.pipeline.PipelineSession;

/**
 * An Executor consumes an approved Artifact and produces a change
 * (typically a diff).
 *
 * The approval gate sits between the Producer and the Executor — no
 * Executor should run against an artifact whose status is not APPROVED.
 *
 * No executors are registered yet. Batch 2B will add one: an
 * ImplementerExecutor that consumes ArtifactKind.PLAN, runs the existing
 * ImplementerService, and writes the resulting diff back into the
 * session's implementationResult.
 */
public interface Executor {

    /** Which artifact kind this executor consumes. */
    ArtifactKind consumes();

    /**
     * Run the executor against the approved artifact.
     * Returns true if the execution completed successfully.
     */
    boolean execute(Artifact artifact, PipelineSession session);
}