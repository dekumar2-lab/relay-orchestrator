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
 * Produces a code analysis document ("explain how X works").
 * Tool: submit_analysis
 * Filled by: AgentRole.ANALYST.
 *
 * Unlike PLAN/DESIGN, this producer never proposes changes — it only
 * describes existing code. The callChain field is where the parser's
 * calls_out data (populated by 3.7a) shows up in the output.
 */
@Service
public class AnalyzeProducer extends BaseProducer {

    private static final String TOOL = "submit_analysis";

    public AnalyzeProducer(AgentRegistry agentRegistry,
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
        return ArtifactKind.EXPLAIN;
    }

    @Override
    public AgentRole role() {
        return AgentRole.ANALYST;
    }

    @Override
    protected String toolName() {
        return TOOL;
    }

    @Override
    protected void validate(PipelineSession session) {
        if (session.lastResult() == null) {
            throw new IllegalStateException(
                    "Cannot produce an analysis: session has no clarifier result");
        }
    }

    @Override
    protected LlmClient.ToolDefinition buildTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", stringProp(
                "Short title describing the subject of analysis (5-10 words)."));
        props.put("whatItDoes", stringProp(
                "Plain-language description of the subject's purpose. 2-4 sentences."));
        props.put("howItWorks", stringProp(
                "The mechanism. Name the key methods and how they interact. "
                        + "4-8 sentences. Be concrete — reference actual method names."));
        props.put("callChain", stringArrayProp(
                "Ordered list of method calls in the flow, formatted as "
                        + "'ClassName.methodName — one-sentence role in the flow'. "
                        + "Only include calls you can see in the retrieved chunks. "
                        + "Empty array if the flow is not observable."));
        props.put("gotchas", stringArrayProp(
                "Non-obvious behaviors, edge cases, or pitfalls. "
                        + "Empty array if there are none."));

        return new LlmClient.ToolDefinition(
                TOOL,
                "Submit the code analysis. Call this tool exactly once.",
                schema(props, List.of("title", "whatItDoes", "howItWorks")));
    }

    @Override
    protected String buildSystemPrompt(AgentPersona persona) {
        return persona.toSystemPrompt()
                + "\n\nYou produce code analysis documents. You explain what "
                + "existing code does — never how to change it. Do not propose "
                + "refactors, fixes, or improvements.\n\n"
                + "Rules:\n"
                + "- Reference real class and method names from the clarifier's "
                + "analysis and the story. Never invent symbols.\n"
                + "- If the clarifier's analysis is ambiguous about what to "
                + "explain, focus on the class or method the story names "
                + "explicitly.\n"
                + "- Prefer concrete mechanism over restated purpose. "
                + "'It uses a Map keyed by session id and evicts on read' beats "
                + "'it manages state efficiently.'\n"
                + "- callChain must only contain methods you have observed in "
                + "the retrieved context. Do not guess at the full flow.";
    }

    @Override
    protected String buildUserMessage(PipelineSession session) {
        return storySection(session)
                + qaSection(session)
                + clarifierSection(session)
                + "Now produce the code analysis. Call " + TOOL + ".";
    }

    @Override
    protected String assembleMarkdown(Map<String, Object> args,
            PipelineSession session,
            AgentPersona persona) {
        String title = stringOr(args, "title", "Code Analysis");
        String whatItDoes = stringOr(args, "whatItDoes", "(no description provided)");
        String howItWorks = stringOr(args, "howItWorks", "(no mechanism provided)");
        List<String> callChain = toStringList(args.get("callChain"));
        List<String> gotchas = toStringList(args.get("gotchas"));

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("_Analyzed by ").append(persona.displayName()).append("_\n\n");

        sb.append("## What it does\n").append(whatItDoes).append("\n\n");
        sb.append("## How it works\n").append(howItWorks).append("\n\n");

        sb.append("## Call chain\n");
        if (callChain.isEmpty()) {
            sb.append("_(no call chain observed in retrieved context)_\n\n");
        } else {
            sb.append(numbered(callChain)).append("\n");
        }

        sb.append("## Gotchas\n");
        sb.append(bullets(gotchas)).append("\n");

        return sb.toString();
    }
}