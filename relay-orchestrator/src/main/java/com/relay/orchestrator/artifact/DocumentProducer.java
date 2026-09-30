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
 * Produces a documentation artifact (Javadoc, README section, usage guide).
 * Tool: submit_docs
 * Filled by: AgentRole.DOCUMENTER.
 *
 * Doc artifacts are downloaded, not executed — there is no matching
 * Executor. The user copies the markdown into the target file or doc.
 */
@Service
public class DocumentProducer extends BaseProducer {

    private static final String TOOL = "submit_docs";

    public DocumentProducer(AgentRegistry agentRegistry,
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
        return ArtifactKind.DOC;
    }

    @Override
    public AgentRole role() {
        return AgentRole.DOCUMENTER;
    }

    @Override
    protected String toolName() {
        return TOOL;
    }

    @Override
    protected void validate(PipelineSession session) {
        if (session.lastResult() == null) {
            throw new IllegalStateException(
                    "Cannot produce documentation: session has no clarifier result");
        }
    }

    @Override
    protected LlmClient.ToolDefinition buildTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", stringProp(
                "Document title. For Javadoc, use the class name. "
                        + "For a README section, use a short heading."));
        props.put("overview", stringProp(
                "One paragraph on what the documented subject is and why it exists. "
                        + "A reader who has never seen this code should understand "
                        + "the purpose after reading it."));
        props.put("api", stringArrayProp(
                "List of public API entries. Each entry formatted as "
                        + "'signature — what it does, when to use it, what it returns'. "
                        + "Include only what exists in the clarifier's analysis."));
        props.put("examples", stringArrayProp(
                "Short usage examples. Each entry is one fenced code block's worth "
                        + "of content. Empty array if examples are not applicable."));
        props.put("notes", stringArrayProp(
                "Optional caveats, thread-safety notes, or configuration requirements. "
                        + "Empty array if there are none."));

        return new LlmClient.ToolDefinition(
                TOOL,
                "Submit the documentation. Call this tool exactly once.",
                schema(props, List.of("title", "overview", "api")));
    }

    @Override
    protected String buildSystemPrompt(AgentPersona persona) {
        return persona.toSystemPrompt()
                + "\n\nYou produce documentation: Javadoc, README sections, and "
                + "usage guides. You never describe behavior changes.\n\n"
                + "Rules:\n"
                + "- Document only what exists in the clarifier's analysis and "
                + "the story. Do not invent methods, parameters, or behaviors.\n"
                + "- Write for someone who has never seen this code. Avoid "
                + "internal jargon unless you define it.\n"
                + "- Prefer clear prose over marketing language. No superlatives.\n"
                + "- If a method's purpose is unclear from the available context, "
                + "describe it neutrally rather than guessing at intent.";
    }

    @Override
    protected String buildUserMessage(PipelineSession session) {
        return storySection(session)
                + qaSection(session)
                + clarifierSection(session)
                + "Now produce the documentation. Call " + TOOL + ".";
    }

    @Override
    protected String assembleMarkdown(Map<String, Object> args,
            PipelineSession session,
            AgentPersona persona) {
        String title = stringOr(args, "title", "Documentation");
        String overview = stringOr(args, "overview", "(no overview provided)");
        List<String> api = toStringList(args.get("api"));
        List<String> examples = toStringList(args.get("examples"));
        List<String> notes = toStringList(args.get("notes"));

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("_Documented by ").append(persona.displayName()).append("_\n\n");

        sb.append("## Overview\n").append(overview).append("\n\n");

        sb.append("## API / Usage\n");
        sb.append(bullets(api)).append("\n");

        if (!examples.isEmpty()) {
            sb.append("## Examples\n\n");
            for (String example : examples) {
                sb.append("```\n").append(example).append("\n```\n\n");
            }
        }

        if (!notes.isEmpty()) {
            sb.append("## Notes\n");
            sb.append(bullets(notes)).append("\n");
        }

        return sb.toString();
    }
}