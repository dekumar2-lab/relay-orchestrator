package com.relay.orchestrator.artifact;

import com.relay.orchestrator.agent.AgentRole;
import com.relay.orchestrator.pipeline.PipelineSession;

/**
 * A Producer generates an Artifact from a session.
 *
 * Each producer is bound to exactly one ArtifactKind and declares the
 * AgentRole that fills it. Config maps role → persona, so the producer
 * never hardcodes a persona name.
 *
 * Implementations extend BaseProducer, which handles LLM plumbing,
 * token tracking, and prompt assembly. Subclasses only override the
 * prompt fragments and the tool schema.
 */
public interface Producer {

    /** Which kind of artifact this producer generates. */
    ArtifactKind produces();

    /** Which role fills this producer. Config maps role → persona. */
    AgentRole role();

    /** Generate and persist the artifact for this session. */
    Artifact produce(PipelineSession session);
}