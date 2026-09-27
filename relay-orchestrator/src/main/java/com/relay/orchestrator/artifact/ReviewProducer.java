package com.relay.orchestrator.artifact;

import com.relay.orchestrator.agent.AgentPersona;
import com.relay.orchestrator.agent.AgentRegistry;
import com.relay.orchestrator.agent.AgentRole;
import com.relay.orchestrator.connection.ConnectionConfigService;
import com.relay.orchestrator.llm.LlmClient;
import com.relay.orchestrator.llm.LlmClientRouter;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.TokenTrackerService;
import com.relay.orchestrator.pipeline.PipelineSession;
import com.relay.orchestrator.tokens.TokenMetricsService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a REVIEW of a proposed diff.
 * Tool: submit_review
 * Filled by: AgentRole.REVIEWER.
 *
 * Requires session.implementationResult() to be present.
 */
@Service
public class ReviewProducer extends BaseProducer {

    private static final String TOOL = "submit_review";

    public ReviewProducer(AgentRegistry agentRegistry,
            ConnectionConfigService configService,
            LlmClientRouter llmRouter,
            LogBroadcaster logBroadcaster,
            TokenTrackerService tokenTracker,
            TokenMetricsService tokenMetrics,
            ArtifactStore artifactStore) {
        super(agentRegistry, configService, llmRouter, logBroadcaster,
                tokenTracker, tokenMetrics, artifactStore);
    }

    @Override
    protected String extractVerdict(Map<String, Object> args) {
        return stringOr(args, "verdict", "COMMENT");
    }

    @Override
    public ArtifactKind produces() {
        return ArtifactKind.REVIEW;
    }

    @Override
    public AgentRole role() {
        return AgentRole.REVIEWER;
    }

    @Override
    protected String toolName() {
        return TOOL;
    }

    @Override
    protected void validate(PipelineSession session) {
        if (session.implementationResult() == null
                || session.implementationResult().files() == null
                || session.implementationResult().files().isEmpty()) {
            throw new IllegalStateException(
                    "Cannot review: session has no implementation result to review");
        }
    }

    @Override
    protected LlmClient.ToolDefinition buildTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("verdict", enumProp(
                List.of("APPROVE", "REQUEST_CHANGES", "COMMENT"),
                "APPROVE if the diff is correct and complete. "
                        + "REQUEST_CHANGES if there are correctness or completeness issues. "
                        + "COMMENT for observations without blocking."));
        props.put("summary", stringProp(
                "One or two sentences summarizing the review."));
        props.put("issues", stringArrayProp(
                "Each issue formatted as '[severity] location — description'. "
                        + "Severity is one of: BLOCKER, MAJOR, MINOR. Empty array if no issues."));
        props.put("suggestions", stringArrayProp(
                "Non-blocking suggestions for improvement."));

        return new LlmClient.ToolDefinition(
                TOOL,
                "Submit the code review. Call this tool exactly once.",
                schema(props, List.of("verdict", "summary")));
    }

    @Override
    protected String buildSystemPrompt(AgentPersona persona) {
        return persona.toSystemPrompt()
                + "\n\nYou review diffs for correctness and completeness. "
                + "Do not comment on style unless it affects correctness. "
                + "Prefer REQUEST_CHANGES over APPROVE when in doubt. "
                + "Never approve a diff that removes existing code without justification.";
    }

    @Override
    protected String buildUserMessage(PipelineSession session) {
        return storySection(session)
                + clarifierSection(session)
                + diffSection(session)
                + "Now review the diff. Call " + TOOL + ".";
    }

    @Override
    protected String assembleMarkdown(Map<String, Object> args,
            PipelineSession session,
            AgentPersona persona) {
        String verdict = stringOr(args, "verdict", "COMMENT");
        String summary = stringOr(args, "summary", "(no summary)");
        List<String> issues = toStringList(args.get("issues"));
        List<String> suggestions = toStringList(args.get("suggestions"));

        StringBuilder sb = new StringBuilder();
        sb.append("# Code Review\n\n");
        sb.append("_Reviewed by ").append(persona.displayName()).append("_\n\n");
        sb.append("**Verdict:** ").append(verdict).append("\n\n");
        sb.append("## Summary\n").append(summary).append("\n\n");
        sb.append("## Issues\n").append(bullets(issues)).append("\n");
        sb.append("## Suggestions\n").append(bullets(suggestions)).append("\n");

        return sb.toString();
    }
}