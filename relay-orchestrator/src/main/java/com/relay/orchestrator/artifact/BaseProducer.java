package com.relay.orchestrator.artifact;

import com.relay.orchestrator.agent.AgentPersona;
import com.relay.orchestrator.agent.AgentRegistry;
import com.relay.orchestrator.connection.ConnectionConfig;
import com.relay.orchestrator.connection.ConnectionConfigService;
import com.relay.orchestrator.llm.LlmClient;
import com.relay.orchestrator.llm.LlmClientRouter;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.logging.TokenTrackerService;
import com.relay.orchestrator.pipeline.AnsweredQuestion;
import com.relay.orchestrator.pipeline.PipelineSession;
import com.relay.orchestrator.pipeline.impl.ImplementationResult;
import com.relay.orchestrator.service.ClarificationResult;
import com.relay.orchestrator.tokens.TokenMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared plumbing for all producers.
 *
 * Handles: fetching config, assembling the LLM request, calling the
 * router, tracking tokens, parsing the tool-call response, and
 * persisting the artifact. Subclasses provide only:
 * - the tool name and schema
 * - the system prompt fragment
 * - the user message
 * - the markdown assembly from the parsed args
 */
public abstract class BaseProducer implements Producer {

    protected static final Logger log = LoggerFactory.getLogger(BaseProducer.class);

    protected final AgentRegistry agentRegistry;
    protected final ConnectionConfigService configService;
    protected final LlmClientRouter llmRouter;
    protected final LogBroadcaster logBroadcaster;
    protected final TokenTrackerService tokenTracker;
    protected final TokenMetricsService tokenMetrics;
    protected final ArtifactStore artifactStore;

    protected BaseProducer(AgentRegistry agentRegistry,
            ConnectionConfigService configService,
            LlmClientRouter llmRouter,
            LogBroadcaster logBroadcaster,
            TokenTrackerService tokenTracker,
            TokenMetricsService tokenMetrics,
            ArtifactStore artifactStore) {
        this.agentRegistry = agentRegistry;
        this.configService = configService;
        this.llmRouter = llmRouter;
        this.logBroadcaster = logBroadcaster;
        this.tokenTracker = tokenTracker;
        this.tokenMetrics = tokenMetrics;
        this.artifactStore = artifactStore;
    }

    // ------------------------------------------------------------------
    // Template method
    // ------------------------------------------------------------------

    @Override
    public final Artifact produce(PipelineSession session) {
        validate(session);

        AgentPersona persona = agentRegistry.getForRole(role());
        String systemPrompt = buildSystemPrompt(persona);
        String userMessage = buildUserMessage(session);
        LlmClient.ToolDefinition tool = buildTool();

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Generating " + produces() + "..."));

        LlmClient.LlmResponse response = callLlm(session, persona, systemPrompt, userMessage, tool);
        Map<String, Object> args = response.toolCalls().get(0).arguments();

        String markdown = assembleMarkdown(args, session, persona);
        String verdict = extractVerdict(args);
        String id = artifactStore.create(session.id(), produces(), markdown, verdict, persona.name());

        Artifact artifact = artifactStore.find(id).orElseThrow(
                () -> new IllegalStateException("Artifact created but not found: " + id));

        logBroadcaster.publish(LogEvent.success(
                "[" + persona.displayName() + "] "
                        + produces() + " artifact " + id.substring(0, 12) + " created"));

        return artifact;
    }

    /**
     * Optional verdict extraction. Only review-like producers return
     * a non-null verdict. Default: null.
     */
    protected String extractVerdict(Map<String, Object> args) {
        return null;
    }

    // ------------------------------------------------------------------
    // Subclass hooks
    // ------------------------------------------------------------------

    /** Unique tool name for this producer's structured output. */
    protected abstract String toolName();

    /** Tool schema definition returned to the LLM. */
    protected abstract LlmClient.ToolDefinition buildTool();

    /** Full system prompt for the persona handling this producer. */
    protected abstract String buildSystemPrompt(AgentPersona persona);

    /** User message containing everything the LLM needs to produce output. */
    protected abstract String buildUserMessage(PipelineSession session);

    /** Assemble markdown content from the parsed tool-call arguments. */
    protected abstract String assembleMarkdown(Map<String, Object> args,
            PipelineSession session,
            AgentPersona persona);

    /** Precondition check. Default: no validation. */
    protected void validate(PipelineSession session) {
        // subclasses can override
    }

    // ------------------------------------------------------------------
    // LLM call
    // ------------------------------------------------------------------

    protected LlmClient.LlmResponse callLlm(PipelineSession session,
            AgentPersona persona,
            String systemPrompt,
            String userMessage,
            LlmClient.ToolDefinition tool) {

        ConnectionConfig config = configService.load()
                .orElseThrow(() -> new IllegalStateException("No connection config loaded"));

        String model = persona.modelOverride().orElse(config.getModel());
        int maxTokens = persona.maxTokensOverride().orElse(4000);
        double temperature = persona.temperatureOverride().orElse(0.0);

        LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                systemPrompt,
                List.of(new LlmClient.Message("user", userMessage)),
                model, maxTokens, temperature,
                List.of(tool),
                LlmClient.ToolChoice.specific(toolName()));

        LlmClient.LlmResponse response;
        try {
            response = llmRouter.complete(config, request);
        } catch (Exception e) {
            log.error("Producer LLM call failed for {}", produces(), e);
            logBroadcaster.publish(LogEvent.error(
                    "[" + persona.displayName() + "] LLM call failed: " + e.getMessage()));
            throw new RuntimeException("Producer LLM call failed: " + e.getMessage(), e);
        }

        tokenTracker.logUsage(
                "PRODUCER_" + produces(),
                response.modelUsed(),
                response.inputTokens(),
                response.outputTokens());

        tokenMetrics.record(
                session.id(),
                role(),
                persona.name(),
                response.modelUsed(),
                0,
                response.totalInputTokens(),
                0,
                response.outputTokens(),
                response.cacheReadTokens(),
                response.cacheWriteTokens());

        if (!response.hasToolCalls()) {
            log.error("Producer {} returned no tool call", produces());
            throw new RuntimeException("Model did not call the " + toolName() + " tool");
        }

        return response;
    }

    // ------------------------------------------------------------------
    // Prompt assembly helpers
    // ------------------------------------------------------------------

    protected String storySection(PipelineSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("STORY:\n").append(session.originalStory()).append("\n\n");
        return sb.toString();
    }

    protected String qaSection(PipelineSession session) {
        List<AnsweredQuestion> qa = session.qaHistory();
        if (qa == null || qa.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append("USER ANSWERS:\n");
        int i = 1;
        for (AnsweredQuestion a : qa) {
            if (a.question() != null && !a.question().isBlank()) {
                sb.append("Q").append(i).append(": ").append(a.question()).append("\n");
            }
            sb.append("A").append(i).append(": ").append(a.answer()).append("\n\n");
            i++;
        }
        return sb.toString();
    }

    protected String clarifierSection(PipelineSession session) {
        ClarificationResult c = session.lastResult();
        if (c == null)
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append("CLARIFIER ANALYSIS:\n");
        sb.append("Summary: ").append(c.summary()).append("\n");
        sb.append("Confidence: ").append(c.confidence()).append("\n");
        sb.append("Complexity: ").append(c.estimatedComplexity()).append("\n");

        if (c.relevantFiles() != null && !c.relevantFiles().isEmpty()) {
            sb.append("Relevant files: ").append(String.join(", ", c.relevantFiles())).append("\n");
        }
        if (c.symbolsLikelyAffected() != null && !c.symbolsLikelyAffected().isEmpty()) {
            sb.append("Symbols likely affected: ").append(String.join(", ", c.symbolsLikelyAffected())).append("\n");
        }
        if (c.riskNotes() != null && !c.riskNotes().isEmpty()) {
            sb.append("Known risks:\n");
            for (String r : c.riskNotes())
                sb.append("- ").append(r).append("\n");
        }
        if (c.assumptionsMade() != null && !c.assumptionsMade().isEmpty()) {
            sb.append("Assumptions:\n");
            for (String a : c.assumptionsMade())
                sb.append("- ").append(a).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    protected String diffSection(PipelineSession session) {
        ImplementationResult impl = session.implementationResult();
        if (impl == null || impl.files() == null || impl.files().isEmpty()) {
            return "(no diff available)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("PROPOSED DIFF:\n");
        sb.append("Summary: ").append(impl.summary()).append("\n");
        sb.append("Totals: +").append(impl.totalAdditions())
                .append(" / -").append(impl.totalDeletions())
                .append(" across ").append(impl.files().size()).append(" file(s)\n\n");

        for (ImplementationResult.FileDiff f : impl.files()) {
            sb.append("=== ").append(f.changeKind()).append(" ").append(f.path()).append(" ===\n");
            sb.append(f.unifiedDiff()).append("\n\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Tool schema helpers
    // ------------------------------------------------------------------

    protected Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        return s;
    }

    protected Map<String, Object> stringProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", desc);
        return p;
    }

    protected Map<String, Object> stringArrayProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("items", Map.of("type", "string"));
        p.put("description", desc);
        return p;
    }

    protected Map<String, Object> enumProp(List<String> values, String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("enum", values);
        p.put("description", desc);
        return p;
    }

    // ------------------------------------------------------------------
    // Argument parsing helpers
    // ------------------------------------------------------------------

    protected String stringOr(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    protected List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list))
            return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null)
                out.add(String.valueOf(item));
        }
        return out;
    }

    protected String bullets(List<String> items) {
        if (items == null || items.isEmpty())
            return "_(none)_";
        StringBuilder sb = new StringBuilder();
        for (String item : items)
            sb.append("- ").append(item).append("\n");
        return sb.toString();
    }

    protected String numbered(List<String> items) {
        if (items == null || items.isEmpty())
            return "_(none)_";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String item : items)
            sb.append(i++).append(". ").append(item).append("\n");
        return sb.toString();
    }
}