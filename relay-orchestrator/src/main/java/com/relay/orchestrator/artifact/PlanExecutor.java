package com.relay.orchestrator.artifact;

import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.pipeline.ClarificationLoopService;
import com.relay.orchestrator.pipeline.PipelineSession;
import com.relay.orchestrator.pipeline.impl.ImplementationResult;
import com.relay.orchestrator.pipeline.impl.ImplementerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Consumes an approved PLAN artifact, runs the implementer with the plan
 * as the primary directive, and marks the artifact EXECUTED on success.
 *
 * Named after the artifact kind it consumes, not the persona. Config maps
 * IMPLEMENTER → persona (jim, dwight, whoever).
 */
@Service
public class PlanExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final ImplementerService implementerService;
    private final ClarificationLoopService loopService;
    private final ArtifactStore artifactStore;
    private final LogBroadcaster logBroadcaster;

    public PlanExecutor(ImplementerService implementerService,
            ClarificationLoopService loopService,
            ArtifactStore artifactStore,
            LogBroadcaster logBroadcaster) {
        this.implementerService = implementerService;
        this.loopService = loopService;
        this.artifactStore = artifactStore;
        this.logBroadcaster = logBroadcaster;
    }

    @Override
    public ArtifactKind consumes() {
        return ArtifactKind.PLAN;
    }

    @Override
    public boolean execute(Artifact artifact, PipelineSession session) {
        if (!artifact.isApproved() && !artifact.isExecuted()) {
            log.warn("Refusing to execute artifact {} in status {}",
                    artifact.id(), artifact.status());
            logBroadcaster.publish(LogEvent.error(
                    "[EXECUTOR] Artifact " + artifact.shortId()
                            + " is not APPROVED or EXECUTED (status=" + artifact.status() + ")"));
            return false;
        }

        try {
            ImplementationResult result = implementerService.implement(session, artifact.content());

            PipelineSession updated = session.withImplementation(result);
            loopService.save(updated);
            artifactStore.markExecuted(artifact.id());

            // A new diff invalidates any previous review
            artifactStore.delete(session.id(), ArtifactKind.REVIEW);

            logBroadcaster.publish(LogEvent.success(
                    "[EXECUTOR] Plan " + artifact.shortId() + " executed (review invalidated)"));

            return true;
        } catch (Exception e) {
            log.error("Executor failed for artifact {}", artifact.id(), e);
            logBroadcaster.publish(LogEvent.error(
                    "[EXECUTOR] Implementation failed: " + e.getMessage()));
            return false;
        }
    }

    public boolean executeWithFeedback(Artifact plan,
            PipelineSession session,
            String reviewFeedback) {
        if (!plan.isApproved() && !plan.isExecuted()) {
            log.warn("Refusing to execute artifact {} in status {}",
                    plan.id(), plan.status());
            logBroadcaster.publish(LogEvent.error(
                    "[EXECUTOR] Artifact " + plan.shortId()
                            + " is not APPROVED or EXECUTED (status=" + plan.status() + ")"));
            return false;
        }
        try {
            ImplementationResult result = implementerService.implement(
                    session, plan.content(), reviewFeedback);

            PipelineSession updated = session.withImplementation(result);
            loopService.save(updated);
            artifactStore.markExecuted(plan.id());

            // A new diff invalidates any previous review
            artifactStore.delete(session.id(), ArtifactKind.REVIEW);

            logBroadcaster.publish(LogEvent.success(
                    "[EXECUTOR] Re-implementation from plan " + plan.shortId()
                            + " complete (review invalidated)"));

            return true;
        } catch (Exception e) {
            log.error("Re-implementation failed for plan {}", plan.id(), e);
            logBroadcaster.publish(LogEvent.error(
                    "[EXECUTOR] Re-implementation failed: " + e.getMessage()));
            return false;
        }
    }
}