package com.relay.orchestrator.service;

import com.relay.orchestrator.agent.AgentPersona;
import com.relay.orchestrator.agent.AgentRegistry;
import com.relay.orchestrator.agent.AgentRole;
import com.relay.orchestrator.config.AppConfigManager;
import com.relay.orchestrator.config.RepoStatus;
import com.relay.orchestrator.config.RepositoryConfig;
import com.relay.orchestrator.connection.ConnectionConfig;
import com.relay.orchestrator.connection.ConnectionConfigService;
import com.relay.orchestrator.llm.LlmClient;
import com.relay.orchestrator.llm.LlmClientRouter;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.logging.TokenTrackerService;
import com.relay.orchestrator.pipeline.AnsweredQuestion;
import com.relay.orchestrator.retrieval.RetrievalService;
import com.relay.orchestrator.retrieval.RetrievedChunk;
import com.relay.orchestrator.tokens.TokenMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IntentClarifierService {

    private static final Logger log = LoggerFactory.getLogger(IntentClarifierService.class);

    private static final String TOOL_SEARCH = "search_code";
    private static final String TOOL_READ = "read_file";
    private static final String TOOL_SUBMIT = "submit_clarification_report";

    private static final int MAX_TURNS = 3;
    private static final int MAX_READ_BYTES = 40_000;
    private static final int MAX_SEARCH_HITS = 6;

    private final LlmClientRouter llmRouter;
    private final ConnectionConfigService configService;
    private final AgentRegistry agentRegistry;
    private final LogBroadcaster logBroadcaster;
    private final TokenTrackerService tokenTracker;
    private final TokenMetricsService tokenMetrics;
    private final RetrievalService retrievalService;
    private final AppConfigManager appConfigManager;

    public IntentClarifierService(LlmClientRouter llmRouter,
            ConnectionConfigService configService,
            AgentRegistry agentRegistry,
            LogBroadcaster logBroadcaster,
            TokenTrackerService tokenTracker,
            TokenMetricsService tokenMetrics,
            RetrievalService retrievalService,
            AppConfigManager appConfigManager) {
        this.llmRouter = llmRouter;
        this.configService = configService;
        this.agentRegistry = agentRegistry;
        this.logBroadcaster = logBroadcaster;
        this.tokenTracker = tokenTracker;
        this.tokenMetrics = tokenMetrics;
        this.retrievalService = retrievalService;
        this.appConfigManager = appConfigManager;
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

        RepositoryConfig repo = pickWorkingRepo();
        Path repoRoot = repo != null
                ? Paths.get(repo.getPath()).toAbsolutePath().normalize()
                : null;
        boolean hasRepo = repoRoot != null;

        String systemPrompt = persona.toSystemPrompt()
                + AGENTIC_GUIDANCE
                + (hasRepo ? "" : NO_REPO_WARNING);

        String model = persona.modelOverride().orElse(config.getModel());
        int maxTokens = persona.maxTokensOverride().orElse(1500);
        double temperature = persona.temperatureOverride().orElse(0.0);

        List<LlmClient.ToolDefinition> tools = buildTools(hasRepo);

        // Pre-seed the conversation with top-K retrieval for the story. The
        // agentic loop can search further; this just guarantees a factual
        // starting point so the model doesn't classify blind.
        List<RetrievedChunk> seedChunks = List.of();
        if (hasRepo) {
            try {
                seedChunks = retrievalService.retrieveForStory(story, 5, 0f, 4000);
            } catch (Exception e) {
                log.warn("Seed retrieval failed: {}", e.getMessage());
            }
        }

        if (!seedChunks.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < seedChunks.size(); i++) {
                if (i > 0)
                    names.append(", ");
                names.append(seedChunks.get(i).chunk().qualifiedName());
            }
            logBroadcaster.publish(LogEvent.info(
                    "[RETRIEVAL] Seeded clarifier with " + seedChunks.size()
                            + " chunk(s): " + names));
        }

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(
                composeUserMessage(story, history, lastChance, seedChunks)));

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Analyzing story: \""
                        + abbreviate(story, 120) + "\""
                        + (history.isEmpty() ? "" : " (with " + history.size() + " prior answers)")
                        + (hasRepo ? " [repo: " + repo.getId() + "]" : " [no repo indexed]")));

        ClarificationResult result = null;
        int turn = 0;
        int maxTurns = MAX_TURNS;

        while (turn < maxTurns) {
            turn++;
            boolean isLastTurn = (turn == maxTurns);

            LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                    systemPrompt,
                    List.copyOf(messages),
                    model, maxTokens, temperature,
                    tools,
                    // Force the submit tool on the final turn so we always get a
                    // structured response. Auto tool choice is unreliable with
                    // Copilot + GPT-4o; the model prefers prose over tool calls.
                    isLastTurn
                            ? LlmClient.ToolChoice.specific(TOOL_SUBMIT)
                            : LlmClient.ToolChoice.auto());

            LlmClient.LlmResponse response;
            try {
                response = llmRouter.complete(config, request);
            } catch (Exception e) {
                log.error("Clarifier turn {} failed", turn, e);
                logBroadcaster.publish(LogEvent.error(
                        "[" + persona.displayName() + "] LLM call failed: " + e.getMessage()));
                return ClarificationResult.error(e.getMessage());
            }

            recordUsage(sessionId, persona, response);

            if (!response.hasToolCalls()) {
                logBroadcaster.publish(LogEvent.info(
                        "[" + persona.displayName() + "] turn " + turn
                                + "/" + maxTurns + " — text only"
                                + (isLastTurn ? " (forced submit ignored)" : ", nudging")));

                String text = response.text() == null ? "" : response.text();
                messages.add(LlmClient.Message.assistant(text));
                messages.add(LlmClient.Message.user(
                        lastChance
                                ? "STOP. Call " + TOOL_SUBMIT + " now. Do not produce any prose."
                                : "Do not answer in prose. Call one of the tools: "
                                        + TOOL_SEARCH + ", " + TOOL_READ + ", or " + TOOL_SUBMIT + "."));
                continue;
            }

            List<String> calledTools = response.toolCalls().stream()
                    .map(LlmClient.ToolCall::name)
                    .toList();
            logBroadcaster.publish(LogEvent.info(
                    "[" + persona.displayName() + "] turn " + turn + "/" + maxTurns
                            + " — tool calls: " + calledTools));

            messages.add(LlmClient.Message.assistantWithToolCalls(response.toolCalls()));

            for (LlmClient.ToolCall call : response.toolCalls()) {
                if (TOOL_SUBMIT.equals(call.name())) {
                    result = fromToolArguments(call.arguments());
                    break;
                }
                String resultText;
                try {
                    resultText = dispatchTool(call, repoRoot);
                } catch (Exception e) {
                    log.warn("Clarifier tool {} failed: {}", call.name(), e.getMessage());
                    resultText = "ERROR: " + e.getMessage();
                }
                messages.add(LlmClient.Message.toolResult(call.id(), resultText));
            }

            if (result != null)
                break;
        }

        if (result == null) {
            log.warn("Clarifier exhausted {} turns without submit; forcing NEEDS_INPUT", MAX_TURNS);
            logBroadcaster.publish(LogEvent.warn(
                    "[" + persona.displayName() + "] No structured output after "
                            + MAX_TURNS + " turns; returning NEEDS_INPUT"));
            return new ClarificationResult(
                    ClarificationResult.State.NEEDS_INPUT,
                    ClarificationResult.Confidence.LOW,
                    "Clarifier could not resolve this story within the turn limit.",
                    List.of(), List.of(),
                    List.of(new ClarifyingQuestion(
                            "q1",
                            "Can you provide more detail about what you want to change?",
                            "The clarifier could not determine the scope from the story alone.",
                            List.of())),
                    List.of(), List.of(), "unknown",
                    ClarificationResult.Intent.PLAN,
                    ClarificationResult.Confidence.LOW);
        }

        logBroadcaster.publish(LogEvent.success(
                "[" + persona.displayName() + "] state=" + result.state()
                        + " intent=" + result.effectiveIntent()
                        + " intentConf=" + result.effectiveIntentConfidence()
                        + " turns=" + turn));

        return result;
    }

    // ------------------------------------------------------------------
    // Tool dispatch
    // ------------------------------------------------------------------

    private String dispatchTool(LlmClient.ToolCall call, Path repoRoot) throws IOException {
        return switch (call.name()) {
            case TOOL_SEARCH -> toolSearch(call.arguments());
            case TOOL_READ -> toolRead(call.arguments(), repoRoot);
            default -> "ERROR: unknown tool " + call.name();
        };
    }

    private String composeUserMessage(String story,
            List<AnsweredQuestion> history,
            boolean lastChance,
            List<RetrievedChunk> seedChunks) {
        StringBuilder sb = new StringBuilder();

        if (seedChunks != null && !seedChunks.isEmpty()) {
            sb.append("INITIAL RETRIEVAL (BM25 over the indexed repo, "
                    + "for the story as written):\n\n");
            for (int i = 0; i < seedChunks.size(); i++) {
                RetrievedChunk rc = seedChunks.get(i);
                sb.append("--- [").append(i + 1).append("] ")
                        .append(rc.chunk().qualifiedName())
                        .append(" (").append(rc.chunk().chunkType())
                        .append(", similarity ")
                        .append(String.format("%.3f", rc.similarity()))
                        .append(") ---\n")
                        .append("file: ").append(rc.chunk().filePath()).append("\n")
                        .append(rc.chunk().content()).append("\n\n");
            }
            sb.append("---\n\n");
            sb.append("These are seeds, not the full answer. Use ")
                    .append(TOOL_SEARCH).append(" and ").append(TOOL_READ)
                    .append(" if you need more, but do not re-search for ")
                    .append("the same thing.\n\n");
        }

        sb.append("Original story:\n").append(story).append("\n\n");

        if (history != null && !history.isEmpty()) {
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
        }

        if (lastChance) {
            sb.append("IMPORTANT: This is the final turn. Do NOT ask any more questions. ")
                    .append("Produce a READY state with the best assumptions you can make, ")
                    .append("or BLOCKED if the story cannot be implemented.\n\n");
        }

        sb.append("Now investigate the codebase if needed, then call ")
                .append(TOOL_SUBMIT).append(".");
        return sb.toString();
    }

    private String toolSearch(Map<String, Object> args) {
        String query = stringOr(args, "query", null);
        if (query == null || query.isBlank())
            return "ERROR: query is required";

        List<RetrievedChunk> hits = retrievalService.retrieveForStory(
                query, MAX_SEARCH_HITS, 0f, 4000);
        if (hits.isEmpty())
            return "No results.";

        StringBuilder sb = new StringBuilder();
        for (RetrievedChunk rc : hits) {
            sb.append("--- ").append(rc.chunk().qualifiedName())
                    .append(" (").append(rc.chunk().chunkType()).append(") ---\n")
                    .append("file: ").append(rc.chunk().filePath()).append("\n")
                    .append(rc.chunk().content()).append("\n\n");
        }
        return sb.toString();
    }

    private String toolRead(Map<String, Object> args, Path repoRoot) throws IOException {
        if (repoRoot == null)
            return "ERROR: no repository is indexed";
        String rel = stringOr(args, "path", null);
        if (rel == null || rel.isBlank())
            return "ERROR: path is required";

        Path target = repoRoot.resolve(rel).normalize();
        if (!target.startsWith(repoRoot))
            return "ERROR: path escapes repo root";
        if (!Files.isRegularFile(target))
            return "ERROR: not a file: " + rel;
        if (Files.size(target) > 200_000) {
            return "ERROR: file too large (" + Files.size(target) + " bytes)";
        }

        String content = Files.readString(target, StandardCharsets.UTF_8);
        if (content.length() > MAX_READ_BYTES) {
            content = content.substring(0, MAX_READ_BYTES)
                    + "\n... [truncated at " + MAX_READ_BYTES + " chars]";
        }
        return content;
    }

    // ------------------------------------------------------------------
    // Tool schemas
    // ------------------------------------------------------------------

    private List<LlmClient.ToolDefinition> buildTools(boolean hasRepo) {
        List<LlmClient.ToolDefinition> tools = new ArrayList<>();

        if (hasRepo) {
            tools.add(new LlmClient.ToolDefinition(
                    TOOL_SEARCH,
                    "Search the indexed codebase using BM25. Returns the top matching "
                            + "class and method chunks with their file paths.",
                    schema(Map.of(
                            "query", stringProp("Natural-language or keyword query")),
                            List.of("query"))));

            tools.add(new LlmClient.ToolDefinition(
                    TOOL_READ,
                    "Read a file's full contents, relative to the repository root. "
                            + "Use this when search_code identified a likely file but you "
                            + "need to see the surrounding code.",
                    schema(Map.of(
                            "path", stringProp("File path relative to repo root")),
                            List.of("path"))));
        }

        tools.add(buildSubmitTool());
        return tools;
    }

    private LlmClient.ToolDefinition buildSubmitTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("state", enumProp(
                List.of("READY", "NEEDS_INPUT", "BLOCKED"),
                "READY if clear enough to proceed. NEEDS_INPUT if the user must answer "
                        + "questions first. BLOCKED if out of scope or nonsensical."));

        properties.put("confidence", enumProp(
                List.of("HIGH", "MEDIUM", "LOW"),
                "HIGH = no assumptions. MEDIUM = low-risk assumptions. LOW = forced to proceed."));

        properties.put("recommendedIntent", enumProp(
                List.of("PLAN", "EXPLAIN", "FIX", "DOCUMENT", "REVIEW"),
                "Which pipeline fits the story. PLAN = multi-file change or unclear "
                        + "approach. EXPLAIN = user wants to understand existing code. "
                        + "FIX = small defect fix, one file or clear two-file scope. "
                        + "DOCUMENT = docs/comments only, no behavior change. "
                        + "REVIEW = audit an existing diff or file."));

        properties.put("intentConfidence", enumProp(
                List.of("HIGH", "MEDIUM", "LOW"),
                "Confidence in recommendedIntent."));

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
                "state", "confidence", "recommendedIntent", "intentConfidence",
                "summary", "estimatedComplexity"));

        return new LlmClient.ToolDefinition(
                TOOL_SUBMIT,
                "Submit the structured analysis. You MUST call this tool to finish.",
                schema);
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

    // ------------------------------------------------------------------
    // Prompt assembly
    // ------------------------------------------------------------------

    private static final String AGENTIC_GUIDANCE = """

            AGENTIC WORKFLOW:
            You have search_code and read_file tools. Use them to verify your
            understanding of the user's codebase before deciding.

            - Search first. Read at most 1-3 of the most relevant files if the
                story references specific classes, methods, or endpoints.
            - Do NOT read every hit. You have at most 4 turns total.
            - When you have enough information, call submit_clarification_report.
                Do not keep reading "just in case."

            INTENT CLASSIFICATION:
            Decide which of these five intents fits the story and set
            recommendedIntent accordingly:

            - PLAN: multi-file change, or a change where the approach is not
                obvious. The pipeline will generate a plan for user approval
                before implementation.
            - EXPLAIN: the user wants to understand existing code. No changes
                will be made.
            - FIX: a small defect fix, one file or a clear two-file scope.
                Skips plan generation and goes straight to implementation.
            - DOCUMENT: docs, comments, or README. No behavior change.
            - REVIEW: the user wants an existing diff or file audited.

            FIX is the right choice when ALL of these hold:
            - The story names a specific class, method, or file.
            - The defect is described concretely (a symptom, a log line, an exception).
            - The fix is likely confined to one or two files.

            If the story names a class AND describes a concrete symptom (a log line,
            an exception, a specific behavior), set intent to FIX. You are
            classifying the work, not designing the fix.

            For FIX intent, do NOT ask clarifying questions about:
            - scope ("does this affect X too?")
            - edge cases ("what about Y scenario?")
            - which logs or events are duplicated
            - severity or frequency
            These are implementation concerns. The implementer will handle them.
            Assume the narrowest scope the story describes and set state to READY.

            A clarifying question is only justified if the story is genuinely
            ambiguous about WHAT to fix, not HOW to fix it. "Fix the duplicate log
            line" is not ambiguous. "Fix the bug in ApplyService" is.

            If any of the three bullets above are false, choose PLAN.

            The word "fix" alone is not enough. "Fix the race condition in
            PipelineOrchestrator" is still PLAN if the fix isn't obvious.

            QUESTIONS:
            - Ask at most 3 questions, and only when the answer would change
            what you would recommend.
            - If the story names a class or file AND describes a concrete symptom,
            you do NOT need to ask where the problem is. Set state to READY.
            - A clarifying question that just says "tell me more" is a failure.
            Ask something actionable, or set READY.
                        """;

    private static final String NO_REPO_WARNING = """

            CRITICAL: NO REPOSITORY IS INDEXED.
            You cannot see the user's code. You MUST set state to NEEDS_INPUT
            and ask clarifying questions about the tech stack and file layout.
            Do NOT set state to READY. Do NOT invent class or method names.
            recommendedIntent should still be your best guess (typically FIX or
            PLAN) but intentConfidence must be LOW.
            """;

    private String composeUserMessage(String story,
            List<AnsweredQuestion> history,
            boolean lastChance) {
        StringBuilder sb = new StringBuilder();

        sb.append("Original story:\n").append(story).append("\n\n");

        if (history != null && !history.isEmpty()) {
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
        }

        if (lastChance) {
            sb.append("IMPORTANT: This is the final turn. Do NOT ask any more questions. ")
                    .append("Produce a READY state with the best assumptions you can make, ")
                    .append("or BLOCKED if the story cannot be implemented. ")
                    .append("Set confidence to LOW if you must proceed without complete info.\n\n");
        }

        sb.append("Now investigate the codebase if needed, then call ")
                .append(TOOL_SUBMIT).append(".");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    private ClarificationResult fromToolArguments(Map<String, Object> args) {
        ClarificationResult.State state = parseState(stringOr(args, "state", "BLOCKED"));
        ClarificationResult.Confidence confidence = parseConfidence(
                stringOr(args, "confidence", "LOW"));
        ClarificationResult.Intent intent = parseIntent(
                stringOr(args, "recommendedIntent", "PLAN"));
        ClarificationResult.Confidence intentConf = parseConfidence(
                stringOr(args, "intentConfidence", "LOW"));

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

        // FIX intent is a fast path. If the model classified the work as FIX
        // but still wants to ask questions, treat the questions as non-blocking
        // and proceed. Rationale: a FIX that asks for scope clarification is
        // still a FIX — the implementer will produce a diff, the user reviews
        // it, and can reject if it's wrong. Blocking a fast path behind
        // clarifying questions defeats the purpose of the fast path.
        if (intent == ClarificationResult.Intent.FIX
                && state == ClarificationResult.State.NEEDS_INPUT) {
            log.info("FIX intent with NEEDS_INPUT — forcing READY "
                    + "(dropping {} questions)", questions.size());
            state = ClarificationResult.State.READY;
            questions = List.of();
            if (confidence == ClarificationResult.Confidence.LOW) {
                confidence = ClarificationResult.Confidence.MEDIUM;
            }
        }

        return new ClarificationResult(
                state, confidence, summary,
                relevantFiles, symbols,
                questions, riskNotes, assumptions, complexity,
                intent, intentConf);
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

    private ClarificationResult.Intent parseIntent(String raw) {
        try {
            return ClarificationResult.Intent.valueOf(raw.toUpperCase());
        } catch (Exception e) {
            return ClarificationResult.Intent.PLAN;
        }
    }

    private List<ClarifyingQuestion> toQuestions(Object raw) {
        if (!(raw instanceof List<?> list))
            return List.of();
        List<ClarifyingQuestion> out = new ArrayList<>();
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m))
                continue;
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void recordUsage(String sessionId, AgentPersona persona, LlmClient.LlmResponse response) {
        tokenTracker.logUsage(
                "CLARIFIER_RUN",
                response.modelUsed(),
                response.inputTokens(),
                response.outputTokens());

        tokenMetrics.record(
                sessionId,
                AgentRole.ORCHESTRATOR,
                persona.name(),
                response.modelUsed(),
                0,
                response.totalInputTokens(),
                0,
                response.outputTokens(),
                response.cacheReadTokens(),
                response.cacheWriteTokens());
    }

    private RepositoryConfig pickWorkingRepo() {
        for (RepositoryConfig repo : appConfigManager.getRepositories()) {
            if (repo.getStatus() == RepoStatus.INDEXED
                    || repo.getStatus() == RepoStatus.STALE) {
                return repo;
            }
        }
        return null;
    }

    private Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        return s;
    }

    private Map<String, Object> stringProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", desc);
        return p;
    }

    private Map<String, Object> stringArrayProp(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("items", Map.of("type", "string"));
        p.put("description", desc);
        return p;
    }

    private Map<String, Object> enumProp(List<String> values, String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("enum", values);
        p.put("description", desc);
        return p;
    }

    private String stringOr(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list))
            return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null)
                out.add(String.valueOf(item));
        }
        return out;
    }

    private String abbreviate(String s, int max) {
        if (s == null)
            return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}