package com.relay.orchestrator.pipeline.impl;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
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
import com.relay.orchestrator.pipeline.PipelineSession;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImplementerService {

    private static final Logger log = LoggerFactory.getLogger(ImplementerService.class);

    private static final int MAX_TURNS = 15;
    private static final int MAX_READ_BYTES = 60_000;
    private static final int MAX_FILE_EDIT_BYTES = 500_000;

    private static final String TOOL_READ = "read_file";
    private static final String TOOL_SEARCH = "search_code";
    private static final String TOOL_WRITE = "write_file";
    private static final String TOOL_EDIT = "edit_file";
    private static final String TOOL_SUBMIT = "submit_plan";

    private final LlmClientRouter llmRouter;
    private final ConnectionConfigService configService;
    private final AgentRegistry agentRegistry;
    private final LogBroadcaster logBroadcaster;
    private final TokenTrackerService tokenTracker;
    private final TokenMetricsService tokenMetrics;
    private final RetrievalService retrievalService;
    private final WorkspaceSessionStore workspace;
    private final AppConfigManager appConfigManager;

    public ImplementerService(LlmClientRouter llmRouter,
            ConnectionConfigService configService,
            AgentRegistry agentRegistry,
            LogBroadcaster logBroadcaster,
            TokenTrackerService tokenTracker,
            TokenMetricsService tokenMetrics,
            RetrievalService retrievalService,
            WorkspaceSessionStore workspace,
            AppConfigManager appConfigManager) {
        this.llmRouter = llmRouter;
        this.configService = configService;
        this.agentRegistry = agentRegistry;
        this.logBroadcaster = logBroadcaster;
        this.tokenTracker = tokenTracker;
        this.tokenMetrics = tokenMetrics;
        this.retrievalService = retrievalService;
        this.workspace = workspace;
        this.appConfigManager = appConfigManager;
    }

    // ==================================================================
    // PLAN-AWARE OVERLOAD
    // ==================================================================

    public ImplementationResult implement(PipelineSession session, String planMarkdown) {
        if (session.lastResult() == null) {
            return failedResult("No clarifier result attached to session", 0);
        }

        RepositoryConfig repo = pickWorkingRepo();
        if (repo == null) {
            return failedResult("No indexed repository available", 0);
        }
        Path repoRoot = Paths.get(repo.getPath()).toAbsolutePath().normalize();
        logBroadcaster.publish(LogEvent.info(
                "[IMPLEMENTER] Working repo: " + repo.getId() + " at " + repoRoot));
        logBroadcaster.publish(LogEvent.info(
                "[IMPLEMENTER] Executing approved plan (" + planMarkdown.length() + " chars)"));

        workspace.reset(session.id());

        ConnectionConfig config = configService.load()
                .orElseThrow(() -> new IllegalStateException("No configuration loaded"));

        AgentPersona persona = agentRegistry.getForRole(AgentRole.IMPLEMENTER);
        String model = persona.modelOverride().orElse(config.getModel());
        int maxTokens = persona.maxTokensOverride().orElse(4000);
        double temperature = persona.temperatureOverride().orElse(0.0);

        String systemPrompt = persona.toSystemPrompt()
                + "\n\n" + IMPLEMENTER_GUIDANCE
                + "\n\nREPO ROOT (relative paths only): " + repoRoot;

        List<LlmClient.ToolDefinition> tools = buildTools();
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(composeUserMessageWithPlan(session, planMarkdown)));

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Implementing: \""
                        + abbreviate(session.originalStory(), 100) + "\""));

        int turn = 0;
        int maxTurns = Math.min(MAX_TURNS, 12);
        String submittedSummary = null;
        String stopReason = "TURN_CAP";

        while (turn < maxTurns) {
            turn++;

            if (turn == 5 && workspace.staged(session.id()).isEmpty()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] No changes staged after 4 turns — prompting to write"));
                messages.add(LlmClient.Message.user(
                        "You have used 4 turns without staging a change. STOP reading. "
                                + "Call edit_file or write_file NOW to stage your best attempt "
                                + "at the fix. You can refine it in later turns."));
            }

            List<LlmClient.ToolDefinition> activeTools = tools;
            if (turn >= 8 && workspace.staged(session.id()).isEmpty()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] Turn " + turn
                                + " with 0 staged changes — restricting tools to write/submit"));
                activeTools = tools.stream()
                        .filter(t -> TOOL_WRITE.equals(t.name()) || TOOL_EDIT.equals(t.name())
                                || TOOL_SUBMIT.equals(t.name()))
                        .toList();
            }

            LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                    systemPrompt,
                    List.copyOf(messages),
                    model,
                    maxTokens,
                    temperature,
                    activeTools,
                    LlmClient.ToolChoice.auto());

            LlmClient.LlmResponse response;
            try {
                response = llmRouter.complete(config, request);
            } catch (Exception e) {
                log.error("Implementer LLM call failed", e);
                logBroadcaster.publish(LogEvent.error(
                        "[IMPLEMENTER] LLM call failed: " + e.getMessage()));
                return failedResult("LLM call failed: " + e.getMessage(), turn);
            }

            tokenTracker.logUsage(
                    "IMPLEMENTER_TURN",
                    response.modelUsed(),
                    response.inputTokens(),
                    response.outputTokens());

            tokenMetrics.record(
                    session.id(),
                    AgentRole.IMPLEMENTER,
                    persona.name(),
                    response.modelUsed(),
                    0,
                    response.totalInputTokens(),
                    0,
                    response.outputTokens(),
                    response.cacheReadTokens(),
                    response.cacheWriteTokens());

            if (!response.hasToolCalls()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] Turn " + turn
                                + " produced no tool calls; nudging agent."));
                messages.add(LlmClient.Message.assistant(
                        response.text() == null ? "" : response.text()));
                messages.add(LlmClient.Message.user(
                        "Please continue using the tools. When the change is "
                                + "complete, call " + TOOL_SUBMIT + "."));
                continue;
            }

            messages.add(LlmClient.Message.assistantWithToolCalls(response.toolCalls()));

            boolean submitThisTurn = false;
            for (LlmClient.ToolCall call : response.toolCalls()) {
                String resultText;
                try {
                    resultText = dispatchTool(call, repoRoot, session.id());
                } catch (Exception e) {
                    log.warn("Tool {} failed: {}", call.name(), e.getMessage());
                    resultText = "ERROR: " + e.getMessage();
                }

                if (TOOL_SUBMIT.equals(call.name())) {
                    Object s = call.arguments().get("summary");
                    submittedSummary = s == null ? "Change submitted" : String.valueOf(s);
                    submitThisTurn = true;
                }

                messages.add(LlmClient.Message.toolResult(call.id(), resultText));
            }

            List<String> calledTools = response.toolCalls().stream()
                    .map(LlmClient.ToolCall::name)
                    .toList();
            logBroadcaster.publish(LogEvent.info(
                    "[IMPLEMENTER] Turn " + turn + " — " + calledTools
                            + ", " + workspace.staged(session.id()).size() + " staged change(s)"));

            if (submitThisTurn) {
                stopReason = "SUBMITTED";
                break;
            }
        }

        ImplementationResult result = buildResult(
                session.id(),
                submittedSummary == null ? "Implementer finished" : submittedSummary,
                turn,
                stopReason);

        logBroadcaster.publish(LogEvent.success(
                "[IMPLEMENTER] " + result.files().size() + " file(s), +"
                        + result.totalAdditions() + "/-" + result.totalDeletions()
                        + " in " + turn + " turn(s)"));

        return result;
    }

    private String composeUserMessageWithPlan(PipelineSession session, String planMarkdown) {
        StringBuilder sb = new StringBuilder();
        sb.append("STORY:\n").append(session.originalStory()).append("\n\n");

        if (!session.qaHistory().isEmpty()) {
            sb.append("USER ANSWERS:\n");
            int i = 1;
            for (AnsweredQuestion a : session.qaHistory()) {
                if (a.question() != null && !a.question().isBlank()) {
                    sb.append("Q").append(i).append(": ").append(a.question()).append("\n");
                }
                sb.append("A").append(i).append(": ").append(a.answer()).append("\n\n");
                i++;
            }
        }

        sb.append("APPROVED IMPLEMENTATION PLAN:\n");
        sb.append("----------------------------------------\n");
        sb.append(planMarkdown).append("\n");
        sb.append("----------------------------------------\n\n");

        sb.append("The plan above is your directive. Execute it faithfully. ");
        sb.append("Follow the \"Files to Change\" section. ");
        sb.append("Use the plan's \"Approach\" section as your checklist. ");
        sb.append("Call ").append(TOOL_SUBMIT).append(" when every step is done.");
        return sb.toString();
    }

    // ==================================================================
    // NO-PLAN OVERLOAD (used by /pipeline/{id}/fix)
    // ==================================================================

    public ImplementationResult implement(PipelineSession session) {
        if (session.lastResult() == null) {
            return failedResult("No clarifier result attached to session", 0);
        }

        RepositoryConfig repo = pickWorkingRepo();
        if (repo == null) {
            return failedResult("No indexed repository available", 0);
        }
        Path repoRoot = Paths.get(repo.getPath()).toAbsolutePath().normalize();
        logBroadcaster.publish(LogEvent.info(
                "[IMPLEMENTER] Working repo: " + repo.getId() + " at " + repoRoot));

        workspace.reset(session.id());

        ConnectionConfig config = configService.load()
                .orElseThrow(() -> new IllegalStateException("No configuration loaded"));

        AgentPersona persona = agentRegistry.getForRole(AgentRole.IMPLEMENTER);
        String model = persona.modelOverride().orElse(config.getModel());
        int maxTokens = persona.maxTokensOverride().orElse(4000);
        double temperature = persona.temperatureOverride().orElse(0.0);

        String systemPrompt = persona.toSystemPrompt()
                + "\n\n" + IMPLEMENTER_GUIDANCE
                + "\n\nREPO ROOT (relative paths only): " + repoRoot;

        List<LlmClient.ToolDefinition> tools = buildTools();
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(composeUserMessage(session)));

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Implementing: \""
                        + abbreviate(session.originalStory(), 100) + "\""));

        int turn = 0;
        int maxTurns = Math.min(MAX_TURNS, 12);
        String submittedSummary = null;
        String stopReason = "TURN_CAP";

        while (turn < maxTurns) {
            turn++;

            if (turn == 5 && workspace.staged(session.id()).isEmpty()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] No changes staged after 4 turns — prompting to write"));
                messages.add(LlmClient.Message.user(
                        "You have used 4 turns without staging a change. STOP reading. "
                                + "Call edit_file or write_file NOW to stage your best attempt "
                                + "at the fix. You can refine it in later turns."));
            }

            List<LlmClient.ToolDefinition> activeTools = tools;
            if (turn >= 8 && workspace.staged(session.id()).isEmpty()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] Turn " + turn
                                + " with 0 staged changes — restricting tools to write/submit"));
                activeTools = tools.stream()
                        .filter(t -> TOOL_WRITE.equals(t.name()) || TOOL_EDIT.equals(t.name())
                                || TOOL_SUBMIT.equals(t.name()))
                        .toList();
            }

            LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                    systemPrompt,
                    List.copyOf(messages),
                    model,
                    maxTokens,
                    temperature,
                    activeTools,
                    LlmClient.ToolChoice.auto());

            LlmClient.LlmResponse response;
            try {
                response = llmRouter.complete(config, request);
            } catch (Exception e) {
                log.error("Implementer LLM call failed", e);
                logBroadcaster.publish(LogEvent.error(
                        "[IMPLEMENTER] LLM call failed: " + e.getMessage()));
                return failedResult("LLM call failed: " + e.getMessage(), turn);
            }

            tokenTracker.logUsage(
                    "IMPLEMENTER_TURN",
                    response.modelUsed(),
                    response.inputTokens(),
                    response.outputTokens());

            tokenMetrics.record(
                    session.id(),
                    AgentRole.IMPLEMENTER,
                    persona.name(),
                    response.modelUsed(),
                    0,
                    response.totalInputTokens(),
                    0,
                    response.outputTokens(),
                    response.cacheReadTokens(),
                    response.cacheWriteTokens());

            if (!response.hasToolCalls()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] Turn " + turn
                                + " produced no tool calls; nudging agent."));
                messages.add(LlmClient.Message.assistant(
                        response.text() == null ? "" : response.text()));
                messages.add(LlmClient.Message.user(
                        "Please continue using the tools. When the change is "
                                + "complete, call " + TOOL_SUBMIT + "."));
                continue;
            }

            messages.add(LlmClient.Message.assistantWithToolCalls(response.toolCalls()));

            boolean submitThisTurn = false;
            for (LlmClient.ToolCall call : response.toolCalls()) {
                String resultText;
                try {
                    resultText = dispatchTool(call, repoRoot, session.id());
                } catch (Exception e) {
                    log.warn("Tool {} failed: {}", call.name(), e.getMessage());
                    resultText = "ERROR: " + e.getMessage();
                }

                if (TOOL_SUBMIT.equals(call.name())) {
                    Object s = call.arguments().get("summary");
                    submittedSummary = s == null ? "Change submitted" : String.valueOf(s);
                    submitThisTurn = true;
                }

                messages.add(LlmClient.Message.toolResult(call.id(), resultText));
            }

            List<String> calledTools = response.toolCalls().stream()
                    .map(LlmClient.ToolCall::name)
                    .toList();
            logBroadcaster.publish(LogEvent.info(
                    "[IMPLEMENTER] Turn " + turn + " — " + calledTools
                            + ", " + workspace.staged(session.id()).size() + " staged change(s)"));

            if (submitThisTurn) {
                stopReason = "SUBMITTED";
                break;
            }
        }

        ImplementationResult result = buildResult(
                session.id(),
                submittedSummary == null ? "Implementer finished" : submittedSummary,
                turn,
                stopReason);

        logBroadcaster.publish(LogEvent.success(
                "[IMPLEMENTER] " + result.files().size() + " file(s), +"
                        + result.totalAdditions() + "/-" + result.totalDeletions()
                        + " in " + turn + " turn(s)"));

        return result;
    }

    // ==================================================================
    // RE-IMPLEMENT OVERLOAD (plan + review feedback)
    // ==================================================================

    public ImplementationResult implement(PipelineSession session,
            String planMarkdown,
            String reviewFeedback) {

        if (session.lastResult() == null) {
            return failedResult("No clarifier result attached to session", 0);
        }

        RepositoryConfig repo = pickWorkingRepo();
        if (repo == null) {
            return failedResult("No indexed repository available", 0);
        }
        Path repoRoot = Paths.get(repo.getPath()).toAbsolutePath().normalize();
        logBroadcaster.publish(LogEvent.info(
                "[IMPLEMENTER] Working repo: " + repo.getId() + " at " + repoRoot));
        ImplementationResult prev = session.implementationResult();
        int prevFiles = (prev != null && prev.files() != null) ? prev.files().size() : 0;
        logBroadcaster.publish(LogEvent.info(
                "[IMPLEMENTER] Re-implementing with review feedback ("
                        + reviewFeedback.length() + " chars, " + prevFiles
                        + " file(s) in previous diff)"));
        workspace.reset(session.id());

        ConnectionConfig config = configService.load()
                .orElseThrow(() -> new IllegalStateException("No configuration loaded"));

        AgentPersona persona = agentRegistry.getForRole(AgentRole.IMPLEMENTER);
        String model = persona.modelOverride().orElse(config.getModel());
        int maxTokens = persona.maxTokensOverride().orElse(4000);
        double temperature = persona.temperatureOverride().orElse(0.0);

        String systemPrompt = persona.toSystemPrompt()
                + "\n\n" + IMPLEMENTER_GUIDANCE
                + "\n\nREPO ROOT (relative paths only): " + repoRoot;

        List<LlmClient.ToolDefinition> tools = buildTools();
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(
                composeReimplementMessage(session, planMarkdown, reviewFeedback)));

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Re-implementing: \""
                        + abbreviate(session.originalStory(), 100) + "\""));

        int turn = 0;
        int maxTurns = Math.min(MAX_TURNS, 12);
        String submittedSummary = null;
        String stopReason = "TURN_CAP";

        while (turn < maxTurns) {
            turn++;

            if (turn == 5 && workspace.staged(session.id()).isEmpty()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] No changes staged after 4 turns — prompting to write"));
                messages.add(LlmClient.Message.user(
                        "You have used 4 turns without staging a change. STOP reading. "
                                + "Call edit_file or write_file NOW to stage your best attempt "
                                + "at the fix. You can refine it in later turns."));
            }

            List<LlmClient.ToolDefinition> activeTools = tools;
            if (turn >= 8 && workspace.staged(session.id()).isEmpty()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] Turn " + turn
                                + " with 0 staged changes — restricting tools to write/submit"));
                activeTools = tools.stream()
                        .filter(t -> TOOL_WRITE.equals(t.name()) || TOOL_EDIT.equals(t.name())
                                || TOOL_SUBMIT.equals(t.name()))
                        .toList();
            }

            LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                    systemPrompt,
                    List.copyOf(messages),
                    model,
                    maxTokens,
                    temperature,
                    activeTools,
                    LlmClient.ToolChoice.auto());

            LlmClient.LlmResponse response;
            try {
                response = llmRouter.complete(config, request);
            } catch (Exception e) {
                log.error("Implementer LLM call failed", e);
                logBroadcaster.publish(LogEvent.error(
                        "[IMPLEMENTER] LLM call failed: " + e.getMessage()));
                return failedResult("LLM call failed: " + e.getMessage(), turn);
            }

            tokenTracker.logUsage(
                    "IMPLEMENTER_REIMPL",
                    response.modelUsed(),
                    response.inputTokens(),
                    response.outputTokens());

            tokenMetrics.record(
                    session.id(),
                    AgentRole.IMPLEMENTER,
                    persona.name(),
                    response.modelUsed(),
                    0,
                    response.totalInputTokens(),
                    0,
                    response.outputTokens(),
                    response.cacheReadTokens(),
                    response.cacheWriteTokens());

            if (!response.hasToolCalls()) {
                messages.add(LlmClient.Message.assistant(
                        response.text() == null ? "" : response.text()));
                messages.add(LlmClient.Message.user(
                        "Continue using the tools. Call " + TOOL_SUBMIT + " when done."));
                continue;
            }

            messages.add(LlmClient.Message.assistantWithToolCalls(response.toolCalls()));

            boolean submitThisTurn = false;
            for (LlmClient.ToolCall call : response.toolCalls()) {
                String resultText;
                try {
                    resultText = dispatchTool(call, repoRoot, session.id());
                } catch (Exception e) {
                    resultText = "ERROR: " + e.getMessage();
                }
                if (TOOL_SUBMIT.equals(call.name())) {
                    Object s = call.arguments().get("summary");
                    submittedSummary = s == null ? "Change submitted" : String.valueOf(s);
                    submitThisTurn = true;
                }
                messages.add(LlmClient.Message.toolResult(call.id(), resultText));
            }

            List<String> calledTools = response.toolCalls().stream()
                    .map(LlmClient.ToolCall::name)
                    .toList();

            logBroadcaster.publish(LogEvent.info(
                    "[IMPLEMENTER] Turn " + turn + " — " + calledTools
                            + ", " + workspace.staged(session.id()).size() + " staged change(s)"));

            if (submitThisTurn) {
                stopReason = "SUBMITTED";
                break;
            }
        }

        ImplementationResult result = buildResult(
                session.id(),
                submittedSummary == null ? "Re-implemented" : submittedSummary,
                turn, stopReason);

        logBroadcaster.publish(LogEvent.success(
                "[IMPLEMENTER] " + result.files().size() + " file(s), +"
                        + result.totalAdditions() + "/-" + result.totalDeletions()
                        + " in " + turn + " turn(s)"));

        return result;
    }

    private String composeReimplementMessage(PipelineSession session,
            String planMarkdown,
            String reviewFeedback) {
        StringBuilder sb = new StringBuilder();

        sb.append("STORY:\n").append(session.originalStory()).append("\n\n");

        if (planMarkdown != null && !planMarkdown.isBlank()) {
            sb.append("APPROVED PLAN:\n---\n").append(planMarkdown).append("\n---\n\n");
        }

        ImplementationResult prev = session.implementationResult();
        if (prev != null && prev.files() != null && !prev.files().isEmpty()) {
            sb.append("PREVIOUS ATTEMPT (this is the diff that was reviewed and found lacking):\n");
            sb.append("----------------------------------------\n");
            for (ImplementationResult.FileDiff f : prev.files()) {
                sb.append("=== ").append(f.changeKind()).append(" ")
                        .append(f.path()).append(" ===\n");
                sb.append(f.unifiedDiff()).append("\n\n");
            }
            sb.append("----------------------------------------\n\n");
        }

        sb.append("FAILURE REPORT FROM THE TEST HARNESS:\n");
        sb.append("----------------------------------------\n");
        sb.append(reviewFeedback).append("\n");
        sb.append("----------------------------------------\n\n");

        sb.append("CRITICAL GUIDANCE:\n");
        sb.append("- The file on disk is currently PRISTINE. The previous diff was undone ");
        sb.append("before this run. Use read_file to inspect the current state — read_file ");
        sb.append("WILL work and WILL return the original, unmodified file.\n");
        sb.append("- If the report above shows a COMPILATION ERROR, the compiler named ");
        sb.append("specific files and line numbers. Read the file at those exact lines. ");
        sb.append("The fix must make the file compile.\n");
        sb.append("- Do NOT make cosmetic changes to already-valid lines. ");
        sb.append("Do NOT reformat or reorder code that already compiles. ");
        sb.append("Address ONLY the specific error the compiler reported.\n");
        sb.append("- When the previous diff needs to be replaced entirely (e.g. it ");
        sb.append("introduced a syntax error), use write_file with the full corrected ");
        sb.append("file content. Do not use edit_file for that case.\n");
        sb.append("- Before calling submit_plan, verify the file you are producing is ");
        sb.append("syntactically complete: no unbalanced braces, no stray punctuation, ");
        sb.append("no missing semicolons or type declarations.\n\n");

        sb.append("Call ").append(TOOL_SUBMIT).append(" when the fix is staged.");

        return sb.toString();
    }

    // ==================================================================
    // Tools
    // ==================================================================

    private String dispatchTool(LlmClient.ToolCall call, Path repoRoot, String sessionId)
            throws IOException {
        Map<String, Object> args = call.arguments();
        return switch (call.name()) {
            case TOOL_READ -> toolRead(args, repoRoot);
            case TOOL_SEARCH -> toolSearch(args);
            case TOOL_WRITE -> toolWrite(args, repoRoot, sessionId);
            case TOOL_EDIT -> toolEdit(args, repoRoot, sessionId);
            case TOOL_SUBMIT -> "Plan submitted.";
            default -> "ERROR: unknown tool " + call.name();
        };
    }

    private String toolRead(Map<String, Object> args, Path repoRoot) throws IOException {
        String rel = strOr(args, "path", null);
        if (rel == null)
            return "ERROR: path is required";
        Path target = safeResolve(repoRoot, rel);
        if (target == null)
            return "ERROR: path escapes repo root";
        if (!Files.isRegularFile(target))
            return "ERROR: not a file: " + rel;
        if (Files.size(target) > MAX_FILE_EDIT_BYTES) {
            return "ERROR: file too large to read (" + Files.size(target) + " bytes)";
        }
        String content = Files.readString(target, StandardCharsets.UTF_8);
        if (content.length() > MAX_READ_BYTES) {
            content = content.substring(0, MAX_READ_BYTES)
                    + "\n... [truncated at " + MAX_READ_BYTES + " chars]";
        }
        return content;
    }

    private String toolSearch(Map<String, Object> args) {
        String query = strOr(args, "query", null);
        if (query == null || query.isBlank())
            return "ERROR: query is required";
        List<RetrievedChunk> hits = retrievalService.retrieveForStory(query, 8, 0f, 6000);
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

    private String toolWrite(Map<String, Object> args, Path repoRoot, String sessionId)
            throws IOException {
        String rel = strOr(args, "path", null);
        String content = strOr(args, "content", null);
        if (rel == null || content == null) {
            return "ERROR: path and content are required";
        }
        Path target = safeResolve(repoRoot, rel);
        if (target == null)
            return "ERROR: path escapes repo root";

        boolean exists = Files.isRegularFile(target);
        String before = exists ? Files.readString(target, StandardCharsets.UTF_8) : null;

        workspace.add(sessionId, new StagedChange(
                rel,
                exists ? StagedChange.Kind.MODIFY : StagedChange.Kind.CREATE,
                before,
                content));
        return (exists ? "Staged modification of " : "Staged creation of ") + rel;
    }

    private String toolEdit(Map<String, Object> args, Path repoRoot, String sessionId)
            throws IOException {
        String rel = strOr(args, "path", null);
        String oldStr = strOr(args, "old_string", null);
        String newStr = strOr(args, "new_string", null);
        if (rel == null || oldStr == null || newStr == null) {
            return "ERROR: path, old_string, new_string are required";
        }
        Path target = safeResolve(repoRoot, rel);
        if (target == null)
            return "ERROR: path escapes repo root";
        if (!Files.isRegularFile(target))
            return "ERROR: file does not exist: " + rel;

        String before = Files.readString(target, StandardCharsets.UTF_8);
        int first = before.indexOf(oldStr);
        if (first < 0)
            return "ERROR: old_string not found in " + rel;
        int second = before.indexOf(oldStr, first + 1);
        if (second >= 0) {
            return "ERROR: old_string appears " + countOccurrences(before, oldStr)
                    + " times; add more surrounding context to make it unique";
        }
        String after = before.substring(0, first) + newStr + before.substring(first + oldStr.length());

        workspace.add(sessionId, new StagedChange(
                rel, StagedChange.Kind.MODIFY, before, after));
        return "Staged edit of " + rel;
    }

    // ==================================================================
    // Diff generation
    // ==================================================================

    private ImplementationResult buildResult(String sessionId, String summary,
            int turnCount, String stopReason) {
        List<StagedChange> staged = workspace.staged(sessionId);
        List<ImplementationResult.FileDiff> diffs = new ArrayList<>();
        int totalAdd = 0, totalDel = 0;

        for (StagedChange change : staged) {
            List<String> original = change.before() == null
                    ? List.of()
                    : change.before().lines().toList();
            List<String> revised = change.after() == null
                    ? List.of()
                    : change.after().lines().toList();

            Patch<String> patch = DiffUtils.diff(original, revised);
            List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                    "a/" + change.path(), "b/" + change.path(),
                    original, patch, 3);

            int adds = 0, dels = 0;
            for (String line : unified) {
                if (line.startsWith("+") && !line.startsWith("+++"))
                    adds++;
                else if (line.startsWith("-") && !line.startsWith("---"))
                    dels++;
            }

            diffs.add(new ImplementationResult.FileDiff(
                    change.path(),
                    change.kind().name(),
                    String.join("\n", unified),
                    change.before(),
                    change.after(),
                    adds,
                    dels));

            totalAdd += adds;
            totalDel += dels;
        }

        return new ImplementationResult(
                summary, diffs, totalAdd, totalDel,
                turnCount, stopReason, LocalDateTime.now());
    }

    private ImplementationResult failedResult(String reason, int turnCount) {
        return new ImplementationResult(
                reason, List.of(), 0, 0, turnCount, "ERROR", LocalDateTime.now());
    }

    // ==================================================================
    // Prompt + tool schemas
    // ==================================================================

    private static final String IMPLEMENTER_GUIDANCE = """
            You are the IMPLEMENTER agent. You are given a clarifier's structured
            analysis and the user's answers. Your job is to propose the concrete
            code changes that satisfy the story.

            TURN BUDGET:
            You have at most 15 turns. Budget them:
            - Turns 1-3: explore. Read the 1-2 files most likely to contain the fix.
            - Turns 4-10: write. Use edit_file or write_file to stage every change.
            - Turns 11-15: finish. Call submit_plan.

            After turn 5, if you have not staged at least one change, stop reading
            and write. You can always revise in a later turn.

            WORKFLOW:
            1. Use search_code and read_file to locate the target code.
            2. Use edit_file for surgical changes to existing files.
            3. Use write_file to create new files.
            4. When done, call submit_plan with a one-sentence summary.

            RULES:
            - Never write outside the repo root. Paths are relative.
            - Prefer edit_file over write_file for existing files.
            - Keep changes minimal and idiomatic to the codebase's style.
            - Do not modify unrelated files.
            - If you cannot complete the change, still call submit_plan
              and explain why in the summary.
            """;

    private String composeUserMessage(PipelineSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append("STORY:\n").append(session.originalStory()).append("\n\n");

        if (!session.qaHistory().isEmpty()) {
            sb.append("USER ANSWERS:\n");
            int i = 1;
            for (AnsweredQuestion a : session.qaHistory()) {
                if (a.question() != null && !a.question().isBlank()) {
                    sb.append("Q").append(i).append(": ").append(a.question()).append("\n");
                }
                sb.append("A").append(i).append(": ").append(a.answer()).append("\n\n");
                i++;
            }
        }

        if (session.lastResult() != null) {
            sb.append("CLARIFIER SUMMARY:\n")
                    .append(session.lastResult().summary()).append("\n\n");
            if (session.lastResult().assumptionsMade() != null
                    && !session.lastResult().assumptionsMade().isEmpty()) {
                sb.append("ASSUMPTIONS:\n");
                for (String a : session.lastResult().assumptionsMade()) {
                    sb.append("- ").append(a).append("\n");
                }
                sb.append("\n");
            }
        }

        sb.append("Now propose the implementation. Start by searching for "
                + "relevant code, then edit or create the necessary files. "
                + "Call ").append(TOOL_SUBMIT).append(" when done.");
        return sb.toString();
    }

    private List<LlmClient.ToolDefinition> buildTools() {
        return List.of(
                new LlmClient.ToolDefinition(TOOL_SEARCH,
                        "Search the codebase using BM25. Returns the top matching chunks.",
                        schema(Map.of(
                                "query", prop("string", "Natural-language or keyword query")),
                                List.of("query"))),

                new LlmClient.ToolDefinition(TOOL_READ,
                        "Read a file relative to the repo root.",
                        schema(Map.of(
                                "path", prop("string", "File path relative to repo root")),
                                List.of("path"))),

                new LlmClient.ToolDefinition(TOOL_WRITE,
                        "Create a new file or fully overwrite an existing one.",
                        schema(Map.of(
                                "path", prop("string", "File path relative to repo root"),
                                "content", prop("string", "Full file contents")),
                                List.of("path", "content"))),

                new LlmClient.ToolDefinition(TOOL_EDIT,
                        "Surgically replace an exact string in an existing file. "
                                + "old_string must be unique within the file.",
                        schema(Map.of(
                                "path", prop("string", "File path relative to repo root"),
                                "old_string", prop("string", "Exact text to replace, must be unique"),
                                "new_string", prop("string", "Replacement text")),
                                List.of("path", "old_string", "new_string"))),

                new LlmClient.ToolDefinition(TOOL_SUBMIT,
                        "Submit the completed plan. Call this once all edits are staged.",
                        schema(Map.of(
                                "summary", prop("string", "One-sentence summary of the change")),
                                List.of("summary"))));
    }

    private Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        return s;
    }

    private Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private RepositoryConfig pickWorkingRepo() {
        for (RepositoryConfig repo : appConfigManager.getRepositories()) {
            if (repo.getStatus() == RepoStatus.INDEXED || repo.getStatus() == RepoStatus.STALE) {
                return repo;
            }
        }
        return null;
    }

    private Path safeResolve(Path repoRoot, String relative) {
        Path resolved = repoRoot.resolve(relative).normalize();
        if (!resolved.startsWith(repoRoot))
            return null;
        return resolved;
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String strOr(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private String abbreviate(String s, int max) {
        if (s == null)
            return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}