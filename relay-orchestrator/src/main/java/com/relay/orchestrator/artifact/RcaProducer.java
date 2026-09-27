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
 * Produces a root cause analysis from a defect story.
 * Tool: submit_rca
 * Filled by: AgentRole.REVIEWER (whoever fills reviewer produces RCAs).
 */
@Service
public class RcaProducer extends BaseProducer {

    private static final String TOOL = "submit_rca";

    public RcaProducer(AgentRegistry agentRegistry,
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
    public ArtifactKind produces() {
        return ArtifactKind.RCA;
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
        if (session.originalStory() == null || session.originalStory().isBlank()) {
            throw new IllegalStateException(
                    "Cannot generate an RCA: session has no defect description");
        }
    }

    @Override
    protected LlmClient.ToolDefinition buildTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("symptom", stringProp(
                "What the user observes. Restate the defect in one or two sentences."));
        props.put("rootCause", stringProp(
                "The underlying cause. State the mechanism, not the symptom."));
        props.put("evidence", stringArrayProp(
                "Concrete code references (file:line or method signature) supporting the diagnosis."));
        props.put("fix", stringArrayProp(
                "Ordered fix steps. One concrete change per step."));
        props.put("prevention", stringArrayProp(
                "Concrete ways to prevent this class of defect recurring. Empty array if none."));

        return new LlmClient.ToolDefinition(
                TOOL,
                "Submit the root cause analysis. Call this tool exactly once.",
                schema(props, List.of("symptom", "rootCause", "fix")));
    }

    @Override
    protected String buildSystemPrompt(AgentPersona persona) {
        return persona.toSystemPrompt()
                + "\n\nYou produce root cause analyses. State the mechanism, "
                + "not the symptom. Reference real code when you can. "
                + "Do not propose changes to unrelated files.";
    }

    @Override
    protected String buildUserMessage(PipelineSession session) {
        return storySection(session)
                + qaSection(session)
                + clarifierSection(session)
                + "Now produce the root cause analysis. Call " + TOOL + ".";
    }

    @Override
    protected String assembleMarkdown(Map<String, Object> args,
            PipelineSession session,
            AgentPersona persona) {
        String symptom = stringOr(args, "symptom", "(no symptom described)");
        String rootCause = stringOr(args, "rootCause", "(no root cause identified)");
        List<String> evidence = toStringList(args.get("evidence"));
        List<String> fix = toStringList(args.get("fix"));
        List<String> prevention = toStringList(args.get("prevention"));

        StringBuilder sb = new StringBuilder();
        sb.append("# Root Cause Analysis\n\n");
        sb.append("_Authored by ").append(persona.displayName()).append("_\n\n");

        sb.append("## Symptom\n").append(symptom).append("\n\n");
        sb.append("## Root Cause\n").append(rootCause).append("\n\n");
        sb.append("## Evidence\n").append(bullets(evidence)).append("\n");
        sb.append("## Fix\n").append(numbered(fix)).append("\n");
        sb.append("## Prevention\n").append(bullets(prevention)).append("\n");

        return sb.toString();
    }
}