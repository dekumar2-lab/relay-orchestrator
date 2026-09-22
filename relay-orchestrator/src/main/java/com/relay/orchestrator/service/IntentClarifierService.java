package com.relay.orchestrator.service;

import com.relay.orchestrator.agent.AgentPersona;
import com.relay.orchestrator.agent.AgentRegistry;
import com.relay.orchestrator.agent.AgentRole;
import com.relay.orchestrator.connection.ConnectionConfig;
import com.relay.orchestrator.connection.ConnectionConfigService;
import com.relay.orchestrator.llm.LlmClient;
import com.relay.orchestrator.llm.LlmClientRouter;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.logging.TokenTrackerService;
import com.relay.orchestrator.pipeline.AnsweredQuestion;
import com.relay.orchestrator.tokens.TokenMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IntentClarifierService {

    private static final Logger log = LoggerFactory.getLogger(IntentClarifierService.class);

    private static final String TOOL_NAME = "submit_clarification_report";

    private final LlmClientRouter llmRouter;
    private final ConnectionConfigService configService;
    private final AgentRegistry agentRegistry;
    private final LogBroadcaster logBroadcaster;
    private final TokenTrackerService tokenTracker;
    private final TokenMetricsService tokenMetrics;

    public IntentClarifierService(LlmClientRouter llmRouter,
            ConnectionConfigService configService,
            AgentRegistry agentRegistry,
            LogBroadcaster logBroadcaster,
            TokenTrackerService tokenTracker,
            TokenMetricsService tokenMetrics) {
        this.llmRouter = llmRouter;
        this.configService = configService;
        this.agentRegistry = agentRegistry;
        this.logBroadcaster = logBroadcaster;
        this.tokenTracker = tokenTracker;
        this.tokenMetrics = tokenMetrics;
    }

        public ClarificationResult clarify(String story) {
        return clarifyWithHistory(story, UUID.randomUUID().toString(), List.of(), false);
    }

    public ClarificationResult clarifyWithHistory(String story,
                                                   String sessionId,
                                                   List<AnsweredQuestion> history,
                                                   boolean lastChance) {
        if (story == null || story.isBlank()) {
            return ClarificationResult.error("Story input is empty");
        }

        ConnectionConfig config = configService.load()
                .orElseThrow(() -> new IllegalStateException("No configuration loaded"));

        AgentPersona persona = agentRegistry.getForRole(AgentRole.ORCHESTRATOR);

        String systemPrompt = persona.toSystemPrompt();
        String model = persona.modelOverride().orElse(config.getModel());
        int maxTokens = persona.maxTokensOverride().orElse(1200);
        double temperature = persona.temperatureOverride().orElse(0.0);

        String userMessage = composeUserMessage(story, history, lastChance);

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Analyzing story: \"" + abbreviate(story, 120) + "\""
                        + (history.isEmpty() ? "" : " (with " + history.size() + " prior answers)")));

        LlmClient.ToolDefinition tool = buildClarificationTool();

        LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                systemPrompt,
                List.of(new LlmClient.Message("user", userMessage)),
                model,
                maxTokens,
                temperature,
                List.of(tool),
                LlmClient.ToolChoice.specific(TOOL_NAME));

        LlmClient.LlmResponse response;
        try {
            response = llmRouter.complete(config, request);
        } catch (Exception e) {
            log.error("Clarifier LLM call failed", e);
            logBroadcaster.publish(LogEvent.error(
                    "[" + persona.displayName() + "] LLM call failed: " + e.getMessage()));
            return ClarificationResult.error(e.getMessage());
        }

        tokenTracker.logAnthropicUsage(
                "CLARIFIER_RUN",
                response.modelUsed(),
                response.inputTokens(),
                response.outputTokens());

        int estimatedInputBefore = estimateTokens(systemPrompt)
                + estimateTokens(userMessage)
                + 350;
        int estimatedOutputBefore = estimateTokens(
                response.text() == null ? "" : response.text());

        tokenMetrics.record(
                sessionId,
                AgentRole.ORCHESTRATOR,
                persona.name(),
                response.modelUsed(),
                estimatedInputBefore,
                response.totalInputTokens(),
                estimatedOutputBefore,
                response.outputTokens(),
                response.cacheReadTokens(),
                response.cacheWriteTokens());

        if (!response.hasToolCalls()) {
            log.error("Clarifier: model returned no tool call despite tool_choice=specific");
            logBroadcaster.publish(LogEvent.error(
                    "[" + persona.displayName() + "] No structured output returned"));
            return ClarificationResult.error("Model did not call the clarification tool");
        }

        Map<String, Object> args = response.toolCalls().get(0).arguments();
        ClarificationResult result = fromToolArguments(args);

        logBroadcaster.publish(LogEvent.success(
                "[" + persona.displayName() + "] state=" + result.state()
                        + " confidence=" + result.confidence()
                        + " complexity=" + result.estimatedComplexity()
                        + " in=" + response.inputTokens()
                        + " out=" + response.outputTokens()
                        + " cacheRead=" + response.cacheReadTokens()));

        return result;
    }

    /**
     * Compose the single user message including accumulated Q&A context.
     * We use one message rather than a multi-turn history because the
     * clarifier runs one-shot per turn — Anthropic holds no session state.
     */
    private String composeUserMessage(String story, List<AnsweredQuestion> history,
                                       boolean lastChance) {
        if (history == null || history.isEmpty()) {
            return story;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Original story:\n").append(story).append("\n\n");
        sb.append("Previous clarifying questions and answers:\n");

        int qNum = 1;
        for (AnsweredQuestion a : history) {
            sb.append("Q").append(qNum).append(": ").append(a.question()).append("\n");
            if (a.why() != null && !a.why().isBlank()) {
                sb.append("   (why: ").append(a.why()).append(")\n");
            }
            sb.append("A").append(qNum).append(": ").append(a.answer()).append("\n\n");
            qNum++;
        }

        if (lastChance) {
            sb.append("IMPORTANT: This is the final turn. You must NOT ask any more questions. ")
              .append("Produce a READY state with the best assumptions you can make, ")
              .append("or BLOCKED if the story cannot be implemented. ")
              .append("Set confidence to LOW if you are forced to proceed without complete info.\n\n");
        }

        sb.append("Now provide an updated clarification report for the original story.");
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Tool schema (built imperatively — avoids deep Map.of nesting)
    // ----------------------------------------------------------------

    private LlmClient.ToolDefinition buildClarificationTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("state", enumProp(
                List.of("READY", "NEEDS_INPUT", "BLOCKED"),
                "READY if clear enough. NEEDS_INPUT if user must answer questions first. "
                        + "BLOCKED if out of scope or nonsensical."));

        properties.put("confidence", enumProp(
                List.of("HIGH", "MEDIUM", "LOW"),
                "HIGH = no assumptions. MEDIUM = low-risk assumptions. LOW = forced to proceed."));

        properties.put("summary", stringProp(
                "One sentence describing what the user wants."));

        properties.put("estimatedComplexity", enumProp(
                List.of("low", "medium", "high", "unknown"),
                "Rough size estimate."));

        properties.put("riskNotes", stringArrayProp(
                "Concrete risks. Empty array if none."));

        properties.put("assumptionsMade", stringArrayProp(
                "Assumptions filled in for missing info. Empty if none."));

        properties.put("relevantFiles", stringArrayProp(
                "Files likely affected. Empty when no repo context available."));

        properties.put("symbolsLikelyAffected", stringArrayProp(
                "Methods/classes likely touched. Empty when no repo context available."));

        properties.put("questions", questionsProp());

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "state", "confidence", "summary", "estimatedComplexity"));

        return new LlmClient.ToolDefinition(
                TOOL_NAME,
                "Submit the structured analysis of a feature story. "
                        + "You MUST call this tool every time.",
                schema);
    }

    private Map<String, Object> enumProp(List<String> values, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("enum", values);
        p.put("description", description);
        return p;
    }

    private Map<String, Object> stringProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private Map<String, Object> stringArrayProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("items", Map.of("type", "string"));
        p.put("description", description);
        return p;
    }

    private Map<String, Object> questionsProp() {
        Map<String, Object> questionItem = new LinkedHashMap<>();
        questionItem.put("type", "object");
        Map<String, Object> qProps = new LinkedHashMap<>();
        qProps.put("id", stringProp("Stable id like q1, q2"));
        qProps.put("question", stringProp("The question for the user"));
        qProps.put("why", stringProp("Why the answer affects implementation"));
        qProps.put("options", stringArrayProp("Suggested answers. Empty if open-ended."));
        questionItem.put("properties", qProps);
        questionItem.put("required", List.of("id", "question", "why"));

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("items", questionItem);
        p.put("description", "At most 3 questions. Empty array if READY or BLOCKED.");
        return p;
    }

    // ----------------------------------------------------------------
    // Extraction
    // ----------------------------------------------------------------

    private ClarificationResult fromToolArguments(Map<String, Object> args) {
        ClarificationResult.State state = parseState(stringOr(args, "state", "BLOCKED"));
        ClarificationResult.Confidence confidence =
                parseConfidence(stringOr(args, "confidence", "LOW"));
        String summary = stringOr(args, "summary", "");
        String complexity = stringOr(args, "estimatedComplexity", "unknown");

        List<String> riskNotes = toStringList(args.get("riskNotes"));
        List<String> assumptions = toStringList(args.get("assumptionsMade"));
        List<String> relevantFiles = toStringList(args.get("relevantFiles"));
        List<String> symbols = toStringList(args.get("symbolsLikelyAffected"));
        List<ClarifyingQuestion> questions = toQuestions(args.get("questions"));

        if (state == ClarificationResult.State.READY && !questions.isEmpty()) {
            log.warn("Clarifier returned READY with {} questions — downgrading to NEEDS_INPUT",
                    questions.size());
            state = ClarificationResult.State.NEEDS_INPUT;
        }
        if (state == ClarificationResult.State.NEEDS_INPUT && questions.isEmpty()) {
            log.warn("Clarifier returned NEEDS_INPUT with no questions — upgrading to READY");
            state = ClarificationResult.State.READY;
            confidence = ClarificationResult.Confidence.MEDIUM;
        }

        return new ClarificationResult(
                state, confidence, summary,
                relevantFiles, symbols,
                questions, riskNotes, assumptions, complexity);
    }

    private String stringOr(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }

    private List<ClarifyingQuestion> toQuestions(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<ClarifyingQuestion> out = new ArrayList<>();
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String id = m.get("id") != null ? String.valueOf(m.get("id")) : "q" + idx;
            String question = m.get("question") != null ? String.valueOf(m.get("question")) : "";
            String why = m.get("why") != null ? String.valueOf(m.get("why")) : "";
            List<String> options = toStringList(m.get("options"));
            if (!question.isBlank()) {
                out.add(new ClarifyingQuestion(id, question, why, options));
            }
            idx++;
        }
        return out;
    }

    private ClarificationResult.State parseState(String raw) {
        try {
            return ClarificationResult.State.valueOf(raw.toUpperCase());
        } catch (Exception e) {
            return ClarificationResult.State.BLOCKED;
        }
    }

    private ClarificationResult.Confidence parseConfidence(String raw) {
        try {
            return ClarificationResult.Confidence.valueOf(raw.toUpperCase());
        } catch (Exception e) {
            return ClarificationResult.Confidence.LOW;
        }
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        return Math.max(1, (int) (text.length() / 3.5));
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}