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
 * Produces an implementation PLAN from a clarified session.
 * Tool: submit_plan
 * Filled by: whoever is configured as AgentRole.PLANNER.
 */
@Service
public class PlanProducer extends BaseProducer {

    private static final String TOOL = "submit_plan";

    public PlanProducer(AgentRegistry agentRegistry,
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
        return ArtifactKind.PLAN;
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
                    "Cannot generate a plan: session has no clarifier analysis");
        }
    }

    @Override
    protected LlmClient.ToolDefinition buildTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("summary", stringProp(
                "One sentence describing what this plan will build."));
        props.put("filesAffected", stringArrayProp(
                "List of file paths (relative to repo root) that will be modified or created."));
        props.put("approach", stringArrayProp(
                "Ordered steps describing what each file change does. Keep each step to one sentence."));
        props.put("risks", stringArrayProp(
                "Concrete risks. Empty array if none."));
        props.put("outOfScope", stringArrayProp(
                "What this plan explicitly does NOT do. Empty array if nothing."));

        return new LlmClient.ToolDefinition(
                TOOL,
                "Submit the implementation plan. Call this tool exactly once.",
                schema(props, List.of("summary", "filesAffected", "approach")));
    }

    @Override
    protected String buildSystemPrompt(AgentPersona persona) {
        return persona.toSystemPrompt()
                + "\n\nYou produce implementation plans. You never write code. "
                + "Reference real file paths from the clarifier analysis when possible. "
                + "Keep plans under 40 lines. Prefer the simpler of two approaches "
                + "and say so explicitly.";
    }

    @Override
    protected String buildUserMessage(PipelineSession session) {
        return storySection(session)
                + qaSection(session)
                + clarifierSection(session)
                + "Now produce the implementation plan. Call " + TOOL + ".";
    }

    @Override
    protected String assembleMarkdown(Map<String, Object> args,
            PipelineSession session,
            AgentPersona persona) {
        String summary = stringOr(args, "summary", "(no summary)");
        List<String> files = toStringList(args.get("filesAffected"));
        List<String> approach = toStringList(args.get("approach"));
        List<String> risks = toStringList(args.get("risks"));
        List<String> outOfScope = toStringList(args.get("outOfScope"));

        StringBuilder sb = new StringBuilder();
        sb.append("# Implementation Plan\n\n");
        sb.append("_Authored by ").append(persona.displayName()).append("_\n\n");

        sb.append("## Goal\n").append(summary).append("\n\n");

        sb.append("## Files to Change\n");
        if (files.isEmpty()) {
            sb.append("_(none listed)_\n\n");
        } else {
            for (String f : files)
                sb.append("- `").append(f).append("`\n");
            sb.append("\n");
        }

        sb.append("## Approach\n");
        sb.append(numbered(approach)).append("\n");

        sb.append("## Risks\n");
        sb.append(bullets(risks)).append("\n");

        sb.append("## Out of Scope\n");
        sb.append(bullets(outOfScope)).append("\n");

        return sb.toString();
    }
}