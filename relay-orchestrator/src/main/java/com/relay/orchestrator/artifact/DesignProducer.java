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
 * Produces a DESIGN doc from a clarified session.
 * Tool: submit_design
 * Filled by: AgentRole.PLANNER (same role as PLAN, different kind).
 */
@Service
public class DesignProducer extends BaseProducer {

    private static final String TOOL = "submit_design";

    public DesignProducer(AgentRegistry agentRegistry,
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
        return ArtifactKind.DESIGN;
    }

    @Override
    public AgentRole role() {
        return AgentRole.PLANNER;
    }

    @Override
    protected String toolName() {
        return TOOL;
    }

    @Override
    protected void validate(PipelineSession session) {
        if (session.lastResult() == null) {
            throw new IllegalStateException(
                    "Cannot generate a design: session has no clarifier analysis");
        }
    }

    @Override
    protected LlmClient.ToolDefinition buildTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", stringProp("Short design title (5-10 words)."));
        props.put("context", stringProp(
                "Why this design is needed. 2-4 sentences."));
        props.put("proposal", stringProp(
                "The proposed design. 4-8 sentences. Be concrete."));
        props.put("alternatives", stringArrayProp(
                "Other approaches considered, one per entry, formatted as "
                        + "'Name: pros — X, Y; cons — Z'."));
        props.put("tradeoffs", stringArrayProp(
                "Concrete tradeoffs of the chosen approach."));

        return new LlmClient.ToolDefinition(
                TOOL,
                "Submit the design document. Call this tool exactly once.",
                schema(props, List.of("title", "context", "proposal")));
    }

    @Override
    protected String buildSystemPrompt(AgentPersona persona) {
        return persona.toSystemPrompt()
                + "\n\nYou produce design documents. You never write code. "
                + "Stay at the architecture level: components, data flow, "
                + "boundaries. Do not describe individual methods or variables.";
    }

    @Override
    protected String buildUserMessage(PipelineSession session) {
        return storySection(session)
                + qaSection(session)
                + clarifierSection(session)
                + "Now produce the design document. Call " + TOOL + ".";
    }

    @Override
    protected String assembleMarkdown(Map<String, Object> args,
            PipelineSession session,
            AgentPersona persona) {
        String title = stringOr(args, "title", "Untitled Design");
        String context = stringOr(args, "context", "(no context provided)");
        String proposal = stringOr(args, "proposal", "(no proposal provided)");
        List<String> alternatives = toStringList(args.get("alternatives"));
        List<String> tradeoffs = toStringList(args.get("tradeoffs"));

        StringBuilder sb = new StringBuilder();
        sb.append("# Design: ").append(title).append("\n\n");
        sb.append("_Authored by ").append(persona.displayName()).append("_\n\n");

        sb.append("## Context\n").append(context).append("\n\n");
        sb.append("## Proposal\n").append(proposal).append("\n\n");
        sb.append("## Alternatives Considered\n").append(bullets(alternatives)).append("\n");
        sb.append("## Tradeoffs\n").append(bullets(tradeoffs)).append("\n");

        return sb.toString();
    }
}