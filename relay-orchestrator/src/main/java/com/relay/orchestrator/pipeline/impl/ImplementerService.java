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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImplementerService {

    private static final Logger log = LoggerFactory.getLogger(ImplementerService.class);

    private static final int MAX_TURNS = 20;
    private static final int MAX_TURNS_PLAN = 15;
    private static final int MAX_TURNS_NO_PLAN = 10;
    private static final int MAX_POLISH_TURNS = 2;
    private static final int MAX_READ_BYTES = 150_000;
    private static final int MAX_FILE_EDIT_BYTES = 500_000;
    private static final int MAX_COVERAGE_RETRY_TURNS = 3;
    private static final int CONTEXT_PRUNE_KEEP_LAST = 4;
    private static final int CONTEXT_PRUNE_THRESHOLD = 8_000;

    private static final String TOOL_READ = "read_file";
    private static final String TOOL_SEARCH = "search_code";
    private static final String TOOL_WRITE = "write_file";
    private static final String TOOL_EDIT = "edit_file";
    private static final String TOOL_SUBMIT = "submit_plan";

    private static final Pattern BACKTICK_PATH = Pattern.compile("`([^`]+)`");
    private static final Pattern JAVA_PATH = Pattern.compile("([a-zA-Z0-9_/\\\\-]+\\.(?:java|ts|py|js|kt|go))");
    private static final Pattern MARKER_PATTERN = Pattern.compile("\\[(?:NEW|MODIFY)\\]\\s*");

    private final LlmClientRouter llmRouter;
    private final ConnectionConfigService configService;
    private final AgentRegistry agentRegistry;
    private final LogBroadcaster logBroadcaster;
    private final TokenTrackerService tokenTracker;
    private final TokenMetricsService tokenMetrics;
    private final RetrievalService retrievalService;
    private final WorkspaceSessionStore workspace;
    private final AppConfigManager appConfigManager;
    private final ApplyService applyService;

    public ImplementerService(LlmClientRouter llmRouter,
            ConnectionConfigService configService,
            AgentRegistry agentRegistry,
            LogBroadcaster logBroadcaster,
            TokenTrackerService tokenTracker,
            TokenMetricsService tokenMetrics,
            RetrievalService retrievalService,
            WorkspaceSessionStore workspace,
            AppConfigManager appConfigManager,
            ApplyService applyService) {
        this.llmRouter = llmRouter;
        this.configService = configService;
        this.agentRegistry = agentRegistry;
        this.logBroadcaster = logBroadcaster;
        this.tokenTracker = tokenTracker;
        this.tokenMetrics = tokenMetrics;
        this.retrievalService = retrievalService;
        this.workspace = workspace;
        this.appConfigManager = appConfigManager;
        this.applyService = applyService;
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

        // Safety: undo any dirty state a prior crash left behind before
        // staging new changes. No-op on clean repos.
        ApplyResult preUndo = applyService.undo(session);
        if (preUndo.ok()) {
            logBroadcaster.publish(LogEvent.warn(
                    "[IMPLEMENTER] Pre-run undo restored "
                            + preUndo.fileCount() + " file(s) from prior state"));
        }

        List<String> plannedFiles = extractPlanFiles(planMarkdown);
        if (!plannedFiles.isEmpty()) {
            logBroadcaster.publish(LogEvent.info(
                    "[IMPLEMENTER] Plan requires " + plannedFiles.size()
                            + " file(s): " + String.join(", ", plannedFiles)));
        }

        workspace.reset(session.id());

        ConnectionConfig config = configService.load()
                .orElseThrow(() -> new IllegalStateException("No configuration loaded"));
        AgentPersona persona = agentRegistry.getForRole(AgentRole.IMPLEMENTER);

        String systemPrompt = persona.toSystemPrompt()
                + "\n\nREPO ROOT (relative paths only): " + repoRoot;

        List<LlmClient.ToolDefinition> tools = buildTools();
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(composeUserMessageWithPlan(session, planMarkdown)));

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Implementing: \""
                        + abbreviate(session.originalStory(), 100) + "\""));

        return runImplementerLoop(session, config, persona, systemPrompt, tools, messages,
                MAX_TURNS_PLAN, "IMPLEMENTER_TURN", planMarkdown, List.of());
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

        sb.append(planFileChecklist(planMarkdown));

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

        String systemPrompt = persona.toSystemPrompt()
                + "\n\nREPO ROOT (relative paths only): " + repoRoot;

        List<LlmClient.ToolDefinition> tools = buildTools();
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(composeUserMessage(session)));

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Implementing: \""
                        + abbreviate(session.originalStory(), 100) + "\""));

        return runImplementerLoop(session, config, persona, systemPrompt, tools, messages,
                MAX_TURNS_NO_PLAN, "IMPLEMENTER_TURN", "", List.of());
    }

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

        sb.append("Now propose the implementation. Read the relevant file(s), then ");
        sb.append("stage the changes. Call ").append(TOOL_SUBMIT).append(" when done.");
        return sb.toString();
    }

    // ==================================================================
    // RE-IMPLEMENT OVERLOAD (partial — carries forward untouched files)
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

        List<String> plannedFiles = extractPlanFiles(planMarkdown);
        if (!plannedFiles.isEmpty()) {
            logBroadcaster.publish(LogEvent.info(
                    "[IMPLEMENTER] Plan requires " + plannedFiles.size()
                            + " file(s): " + String.join(", ", plannedFiles)));
        }

        workspace.reset(session.id());

        // ---- Carry forward every previous file. Jim's writes will overwrite ----
        List<StagedChange> carried = new ArrayList<>();
        List<String> carriedPaths = new ArrayList<>();
        Set<String> filesUnderReview = extractFilesFromReview(reviewFeedback,
                prev != null ? prev.files() : List.of());

        if (prev != null && prev.files() != null) {
            for (ImplementationResult.FileDiff f : prev.files()) {
                StagedChange.Kind kind = "CREATE".equals(f.changeKind())
                        ? StagedChange.Kind.CREATE
                        : "DELETE".equals(f.changeKind())
                                ? StagedChange.Kind.DELETE
                                : StagedChange.Kind.MODIFY;
                carried.add(new StagedChange(f.path(), kind, f.beforeContent(), f.afterContent()));
                carriedPaths.add(f.path());
            }
        }

        for (StagedChange c : carried) {
            workspace.add(session.id(), c);
        }

        List<String> underReviewPaths = new ArrayList<>();
        for (String p : carriedPaths) {
            if (matchesAny(p, filesUnderReview)) {
                underReviewPaths.add(p);
            }
        }

        logBroadcaster.publish(LogEvent.info(
                "[REIMPL] Already complete (preserved): " + carriedPaths.size() + " file(s)"));
        logBroadcaster.publish(LogEvent.info(
                "[REIMPL] Under revision: " + underReviewPaths));

        ConnectionConfig config = configService.load()
                .orElseThrow(() -> new IllegalStateException("No configuration loaded"));
        AgentPersona persona = agentRegistry.getForRole(AgentRole.IMPLEMENTER);

        String systemPrompt = persona.toSystemPrompt()
                + "\n\nREPO ROOT (relative paths only): " + repoRoot;

        List<LlmClient.ToolDefinition> tools = buildTools();
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(
                composeReimplementMessage(session, planMarkdown, reviewFeedback,
                        filesUnderReview, carriedPaths)));

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Re-implementing: \""
                        + abbreviate(session.originalStory(), 100) + "\""));

        return runImplementerLoop(session, config, persona, systemPrompt, tools, messages,
                MAX_TURNS_PLAN, "IMPLEMENTER_REIMPL", planMarkdown, carried);
    }

    private String composeReimplementMessage(PipelineSession session,
            String planMarkdown,
            String reviewFeedback,
            Set<String> filesUnderReview,
            List<String> carriedPaths) {
        StringBuilder sb = new StringBuilder();

        sb.append("STORY:\n").append(session.originalStory()).append("\n\n");

        if (planMarkdown != null && !planMarkdown.isBlank()) {
            sb.append("APPROVED PLAN:\n---\n").append(planMarkdown).append("\n---\n\n");
            sb.append(planFileChecklist(planMarkdown));
        }

        if (!carriedPaths.isEmpty()) {
            sb.append("ALREADY COMPLETE (pre-loaded and preserved — do NOT touch):\n");
            for (String p : carriedPaths) {
                sb.append("  ✓ ").append(p).append("\n");
            }
            sb.append("These files are already staged. They will remain in the diff ");
            sb.append("automatically. Do NOT read, write, or edit them.\n\n");
        }

        if (!filesUnderReview.isEmpty()) {
            sb.append("FILES YOU MUST REVISE (the review complained about these):\n");
            for (String f : filesUnderReview) {
                sb.append("  [ ] ").append(f).append("\n");
            }
            sb.append("\nStage a NEW version of every file above. Do NOT call submit_plan ");
            sb.append("until each one is staged.\n\n");
        }

        ImplementationResult prev = session.implementationResult();
        if (prev != null && prev.files() != null) {
            sb.append("PREVIOUS ATTEMPT AT THE FILES YOU MUST REVISE:\n");
            sb.append("----------------------------------------\n");
            for (ImplementationResult.FileDiff f : prev.files()) {
                if (!matchesAny(f.path(), filesUnderReview))
                    continue;
                sb.append("=== ").append(f.changeKind()).append(" ")
                        .append(f.path()).append(" ===\n");
                sb.append(f.unifiedDiff()).append("\n\n");
            }
            sb.append("----------------------------------------\n\n");
        }

        sb.append("REVIEW FEEDBACK:\n");
        sb.append("----------------------------------------\n");
        sb.append(reviewFeedback).append("\n");
        sb.append("----------------------------------------\n\n");

        sb.append("CRITICAL GUIDANCE:\n");
        sb.append("- The file on disk is PRISTINE. read_file WILL work.\n");
        sb.append("- Focus ONLY on the files under FILES YOU MUST REVISE.\n");
        sb.append("- Do NOT touch files under ALREADY COMPLETE — they are preserved ");
        sb.append("automatically by the system. Touching them wastes turns.\n");
        sb.append("- Do NOT search_code for Java expressions like 'getX() != null'. ");
        sb.append("Search matches class names, method names, and identifiers — not code snippets.\n");
        sb.append("- If the review names a path, read_file it directly. Do NOT search.\n");
        sb.append("- Use edit_file for surgical changes; write_file for full rewrites.\n");
        sb.append("- Before calling submit_plan, verify every file above is staged and ");
        sb.append("syntactically complete (no unbalanced braces, no partial method bodies).\n\n");

        sb.append("Call ").append(TOOL_SUBMIT).append(" when every revised file is staged.");

        return sb.toString();
    }

    // ==================================================================
    // COMPILE-FIX OVERLOAD (retry after the compile gate fails)
    // ==================================================================

    /**
     * Retry pass. Called by ImplementerVerificationService when the first
     * compile attempt fails. Jim receives his own prior staged files and the
     * compiler output, with read/search disabled — the fix is expected to be
     * local and mechanical.
     */
    public ImplementationResult implementFix(PipelineSession session,
            String compilerErrors,
            List<ImplementationResult.FileDiff> priorFiles) {

        if (session.lastResult() == null) {
            return failedResult("No clarifier result attached to session", 0);
        }

        RepositoryConfig repo = pickWorkingRepo();
        if (repo == null) {
            return failedResult("No indexed repository available", 0);
        }
        Path repoRoot = Paths.get(repo.getPath()).toAbsolutePath().normalize();

        int prior = priorFiles != null ? priorFiles.size() : 0;
        int errLen = compilerErrors != null ? compilerErrors.length() : 0;
        logBroadcaster.publish(LogEvent.info(
                "[IMPLEMENTER] Fix pass — " + prior + " file(s), "
                        + errLen + " chars of compiler output"));

        workspace.reset(session.id());

        ConnectionConfig config = configService.load()
                .orElseThrow(() -> new IllegalStateException("No configuration loaded"));
        AgentPersona persona = agentRegistry.getForRole(AgentRole.IMPLEMENTER);

        String systemPrompt = persona.toSystemPrompt()
                + "\n\nREPO ROOT (relative paths only): " + repoRoot;

        // Restricted tool set — no read_file, no search_code. The prior
        // file contents are already in the prompt.
        List<LlmClient.ToolDefinition> fixTools = buildTools().stream()
                .filter(t -> TOOL_WRITE.equals(t.name())
                        || TOOL_EDIT.equals(t.name())
                        || TOOL_SUBMIT.equals(t.name()))
                .toList();

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user(
                composeFixMessage(session, compilerErrors, priorFiles)));

        logBroadcaster.publish(LogEvent.info(
                "[" + persona.displayName() + "] Fixing compile errors"));

        return runImplementerLoop(session, config, persona, systemPrompt,
                fixTools, messages, 4, "IMPLEMENTER_FIX", "", List.of());
    }

    private String composeFixMessage(PipelineSession session,
            String compilerErrors,
            List<ImplementationResult.FileDiff> priorFiles) {
        StringBuilder sb = new StringBuilder();

        sb.append("STORY:\n").append(session.originalStory()).append("\n\n");

        sb.append("COMPILER ERRORS:\n");
        sb.append("----------------------------------------\n");
        sb.append(compilerErrors == null ? "(no output)" : compilerErrors).append("\n");
        sb.append("----------------------------------------\n\n");

        sb.append("FILES YOU WROTE (current staged contents):\n");
        if (priorFiles == null || priorFiles.isEmpty()) {
            sb.append("(none)\n\n");
        } else {
            for (ImplementationResult.FileDiff f : priorFiles) {
                sb.append("=== ").append(f.changeKind()).append(" ")
                        .append(f.path()).append(" ===\n");
                if (f.afterContent() != null) {
                    sb.append(f.afterContent()).append("\n");
                } else {
                    sb.append("(file was deleted)\n");
                }
                sb.append("\n");
            }
        }

        sb.append("Fix every compiler error above. ");
        sb.append("Use write_file to fully rewrite a file, or edit_file for a ");
        sb.append("surgical fix. Do not search or read — every file you staged ");
        sb.append("is shown above. ");
        sb.append("Call ").append(TOOL_SUBMIT).append(" when every error is resolved.");

        return sb.toString();
    }

    // ==================================================================
    // SHARED IMPLEMENTER LOOP
    // ==================================================================

    private ImplementationResult runImplementerLoop(PipelineSession session,
            ConnectionConfig config,
            AgentPersona persona,
            String systemPrompt,
            List<LlmClient.ToolDefinition> tools,
            List<LlmClient.Message> messages,
            int maxTurns,
            String usageLabel,
            String planMarkdown,
            List<StagedChange> carriedForward) {

        List<String> plannedFiles = extractPlanFiles(planMarkdown);
        int carriedCount = carriedForward != null ? carriedForward.size() : 0;

        int turn = 0;
        int lastStagedCount = carriedCount;
        int polishTurns = 0;
        String submittedSummary = null;
        String stopReason = "TURN_CAP";

        while (turn < maxTurns) {
            turn++;

            if (turn == 5 && workspace.staged(session.id()).size() <= carriedCount) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] No new files staged after 4 turns — prompting to write"));
                messages.add(LlmClient.Message.user(
                        "You have used 4 turns without staging a new file. STOP reading. "
                                + "Call write_file or edit_file NOW to stage your best attempt."));
            }

            List<LlmClient.ToolDefinition> activeTools = tools;
            if (turn >= 8 && workspace.staged(session.id()).size() <= carriedCount) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] Turn " + turn
                                + " — no new files; restricting tools to write/submit"));
                activeTools = tools.stream()
                        .filter(t -> TOOL_WRITE.equals(t.name())
                                || TOOL_EDIT.equals(t.name())
                                || TOOL_SUBMIT.equals(t.name()))
                        .toList();
            }

            int currentStaged = workspace.staged(session.id()).size();
            if (currentStaged > 0 && currentStaged == lastStagedCount) {
                polishTurns++;
            } else {
                polishTurns = 0;
                lastStagedCount = currentStaged;
            }

            if (polishTurns >= MAX_POLISH_TURNS && turn < maxTurns) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] Turn " + turn + " — polish detected; forcing progress"));
                messages.add(LlmClient.Message.user(
                        "Stop editing the same files. If the plan lists more files, "
                                + "stage the NEXT one now. If everything is done, "
                                + "call " + TOOL_SUBMIT + " immediately."));
                polishTurns = 0;
            }

            boolean isLastTurn = (turn == maxTurns);
            LlmClient.ToolChoice toolChoice = isLastTurn
                    ? LlmClient.ToolChoice.specific(TOOL_SUBMIT)
                    : LlmClient.ToolChoice.auto();

            LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                    systemPrompt,
                    List.copyOf(messages),
                    persona.modelOverride().orElse(config.getModel()),
                    persona.maxTokensOverride().orElse(4000),
                    persona.temperatureOverride().orElse(0.0),
                    activeTools,
                    toolChoice);

            LlmClient.LlmResponse response;
            try {
                response = llmRouter.complete(config, request);
            } catch (Exception e) {
                log.error("Implementer LLM call failed", e);
                logBroadcaster.publish(LogEvent.error(
                        "[IMPLEMENTER] LLM call failed: " + e.getMessage()));
                return partialResult(session, submittedSummary, turn,
                        "LLM_CALL_FAILED", plannedFiles);
            }

            tokenTracker.logUsage(usageLabel, response.modelUsed(),
                    response.inputTokens(), response.outputTokens());
            tokenMetrics.record(session.id(), AgentRole.IMPLEMENTER, persona.name(),
                    response.modelUsed(), 0, response.totalInputTokens(), 0,
                    response.outputTokens(), response.cacheReadTokens(),
                    response.cacheWriteTokens());

            if (!response.hasToolCalls()) {
                logBroadcaster.publish(LogEvent.warn(
                        "[IMPLEMENTER] Turn " + turn + " produced no tool calls; nudging"));
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
                    resultText = dispatchTool(call, pickWorkingRepoRoot(), session.id());
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

            pruneOldToolResults(messages);

            List<String> calledTools = response.toolCalls().stream()
                    .map(this::formatToolCall)
                    .toList();
            logBroadcaster.publish(LogEvent.info(
                    "[IMPLEMENTER] Turn " + turn + " — " + calledTools
                            + " · coverage " + coverageString(session, plannedFiles)));

            if (submitThisTurn) {
                stopReason = "SUBMITTED";
                break;
            }
        }

        // ---- Coverage retry: force any missing plan files ----
        List<String> missing = computeMissingFiles(session, plannedFiles);
        if (!missing.isEmpty() && turn < MAX_TURNS) {
            logBroadcaster.publish(LogEvent.warn(
                    "[IMPLEMENTER] Coverage gap: " + missing.size()
                            + " file(s) missing. Forcing retry with write-only tools."));

            messages.add(LlmClient.Message.user(
                    "COVERAGE FAILURE. The plan requires the following files "
                            + "which are NOT yet staged:\n"
                            + String.join("\n", missing.stream().map(p -> "  - " + p).toList())
                            + "\n\nYour ONLY task now is to stage these files. "
                            + "read_file is disabled. search_code is disabled. "
                            + "Use write_file (for [NEW]) or edit_file (for [MODIFY]) "
                            + "on each path above, one per call. Do NOT call submit_plan "
                            + "until every path above is staged."));

            int retryTurns = 0;
            while (retryTurns < MAX_COVERAGE_RETRY_TURNS && turn < MAX_TURNS) {
                turn++;
                retryTurns++;

                List<LlmClient.ToolDefinition> forceWrite = tools.stream()
                        .filter(t -> TOOL_WRITE.equals(t.name())
                                || TOOL_EDIT.equals(t.name())
                                || TOOL_SUBMIT.equals(t.name()))
                        .toList();

                LlmClient.LlmRequest retryReq = new LlmClient.LlmRequest(
                        systemPrompt,
                        List.copyOf(messages),
                        persona.modelOverride().orElse(config.getModel()),
                        persona.maxTokensOverride().orElse(4000),
                        persona.temperatureOverride().orElse(0.0),
                        forceWrite,
                        retryTurns == MAX_COVERAGE_RETRY_TURNS
                                ? LlmClient.ToolChoice.specific(TOOL_SUBMIT)
                                : LlmClient.ToolChoice.auto());

                LlmClient.LlmResponse resp;
                try {
                    resp = llmRouter.complete(config, retryReq);
                } catch (Exception e) {
                    break;
                }

                tokenTracker.logUsage(usageLabel + "_RETRY", resp.modelUsed(),
                        resp.inputTokens(), resp.outputTokens());
                tokenMetrics.record(session.id(), AgentRole.IMPLEMENTER, persona.name(),
                        resp.modelUsed(), 0, resp.totalInputTokens(), 0,
                        resp.outputTokens(), resp.cacheReadTokens(),
                        resp.cacheWriteTokens());

                if (!resp.hasToolCalls())
                    continue;
                messages.add(LlmClient.Message.assistantWithToolCalls(resp.toolCalls()));

                for (LlmClient.ToolCall call : resp.toolCalls()) {
                    String rt;
                    try {
                        rt = dispatchTool(call, pickWorkingRepoRoot(), session.id());
                    } catch (Exception e) {
                        rt = "ERROR: " + e.getMessage();
                    }
                    if (TOOL_SUBMIT.equals(call.name())) {
                        Object s = call.arguments().get("summary");
                        submittedSummary = s == null ? "Recovered" : String.valueOf(s);
                    }
                    messages.add(LlmClient.Message.toolResult(call.id(), rt));
                }

                List<String> retryTools = resp.toolCalls().stream()
                        .map(this::formatToolCall).toList();
                logBroadcaster.publish(LogEvent.info(
                        "[IMPLEMENTER] Retry " + retryTurns + " — " + retryTools
                                + " · coverage " + coverageString(session, plannedFiles)));

                if (resp.toolCalls().stream()
                        .anyMatch(c -> TOOL_SUBMIT.equals(c.name()))) {
                    break;
                }
            }

            missing = computeMissingFiles(session, plannedFiles);
        }

        List<String> finalMissing = computeMissingFiles(session, plannedFiles);
        if (!finalMissing.isEmpty()) {
            logBroadcaster.publish(LogEvent.warn(
                    "[IMPLEMENTER] Coverage incomplete — still missing: " + finalMissing));
            stopReason = "INCOMPLETE";
        } else if (!plannedFiles.isEmpty()) {
            logBroadcaster.publish(LogEvent.info(
                    "[IMPLEMENTER] Coverage OK — all " + plannedFiles.size()
                            + " plan file(s) staged"));
        }

        return partialResult(session, submittedSummary, turn, stopReason, plannedFiles);
    }

    // ==================================================================
    // COVERAGE / LOGGING HELPERS
    // ==================================================================

    private String formatToolCall(LlmClient.ToolCall call) {
        String name = call.name();
        Map<String, Object> args = call.arguments();
        if (args == null || args.isEmpty())
            return name;
        String key = switch (name) {
            case TOOL_READ -> "path";
            case TOOL_SEARCH -> "query";
            case TOOL_WRITE -> "path";
            case TOOL_EDIT -> "path";
            default -> null;
        };
        if (key == null)
            return name;
        Object val = args.get(key);
        if (val == null)
            return name;
        String s = String.valueOf(val);
        if (s.length() > 50)
            s = s.substring(0, 47) + "...";
        return name + "(" + key + "=" + s + ")";
    }

    private String coverageString(PipelineSession session, List<String> plannedFiles) {
        if (plannedFiles.isEmpty()) {
            return workspace.staged(session.id()).size() + " staged";
        }
        Set<String> staged = new HashSet<>();
        for (StagedChange c : workspace.staged(session.id())) {
            staged.add(c.path().replace('\\', '/'));
        }
        int covered = 0;
        for (String p : plannedFiles) {
            String norm = p.replace('\\', '/');
            for (String s : staged) {
                if (s.equals(norm) || s.endsWith(norm) || norm.endsWith(s)) {
                    covered++;
                    break;
                }
            }
        }
        return covered + "/" + plannedFiles.size();
    }

    private List<String> computeMissingFiles(PipelineSession session,
            List<String> plannedFiles) {
        List<String> missing = new ArrayList<>();
        if (plannedFiles.isEmpty())
            return missing;
        Set<String> staged = new HashSet<>();
        for (StagedChange c : workspace.staged(session.id())) {
            staged.add(c.path().replace('\\', '/'));
        }
        for (String p : plannedFiles) {
            String norm = p.replace('\\', '/');
            boolean present = false;
            for (String s : staged) {
                if (s.equals(norm) || s.endsWith(norm) || norm.endsWith(s)) {
                    present = true;
                    break;
                }
            }
            if (!present)
                missing.add(p);
        }
        return missing;
    }

    private void pruneOldToolResults(List<LlmClient.Message> messages) {
        int kept = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmClient.Message m = messages.get(i);
            if ("tool".equals(m.role())) {
                kept++;
                if (kept > CONTEXT_PRUNE_KEEP_LAST) {
                    String content = m.content() == null ? "" : m.content();
                    if (content.length() > CONTEXT_PRUNE_THRESHOLD) {
                        messages.set(i, LlmClient.Message.toolResult(
                                m.toolCallId(),
                                "... [earlier tool result omitted to save context]"));
                    }
                }
            }
        }
    }

    // ==================================================================
    // PLAN FILE EXTRACTION
    // ==================================================================

    private List<String> extractPlanFiles(String markdown) {
        List<String> files = new ArrayList<>();
        if (markdown == null || markdown.isBlank())
            return files;

        boolean inFiles = false;
        for (String line : markdown.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("## ")) {
                inFiles = t.substring(3).toLowerCase().contains("file");
                continue;
            }
            if (!inFiles)
                continue;

            Matcher m = BACKTICK_PATH.matcher(t);
            boolean anyMatched = false;
            while (m.find()) {
                String path = MARKER_PATTERN.matcher(m.group(1)).replaceFirst("").trim();
                if (path.contains(".") && !path.startsWith("http")) {
                    files.add(path);
                    anyMatched = true;
                }
            }
            if (!anyMatched && t.startsWith("- ")) {
                String clean = MARKER_PATTERN.matcher(
                        t.substring(2).replaceAll("[`*]", "").trim())
                        .replaceFirst("").trim();
                if (!clean.isEmpty() && clean.contains(".") && !clean.contains(" ")) {
                    files.add(clean);
                }
            }
        }
        return files;
    }

    private List<String> extractPlanFilesWithMarkers(String markdown) {
        List<String> files = new ArrayList<>();
        if (markdown == null || markdown.isBlank())
            return files;

        boolean inFiles = false;
        for (String line : markdown.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("## ")) {
                inFiles = t.substring(3).toLowerCase().contains("file");
                continue;
            }
            if (!inFiles)
                continue;

            Matcher m = BACKTICK_PATH.matcher(t);
            while (m.find()) {
                String raw = m.group(1).trim();
                if (raw.contains(".") && !raw.startsWith("http")) {
                    files.add(raw);
                }
            }
        }
        return files;
    }

    private String planFileChecklist(String planMarkdown) {
        List<String> files = extractPlanFilesWithMarkers(planMarkdown);
        if (files.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append("REQUIRED FILES (from the plan — every one must be staged before submit):\n");
        for (String f : files) {
            sb.append("  [ ] ").append(f).append("\n");
        }
        sb.append("\n[NEW] = file does not exist; create it with write_file.\n");
        sb.append("[MODIFY] = file exists; read it first, then edit.\n");
        sb.append("Do NOT call submit_plan until every file above is staged.\n\n");
        return sb.toString();
    }

    private Set<String> extractFilesFromReview(String reviewText,
            List<ImplementationResult.FileDiff> previousFiles) {
        Set<String> files = new HashSet<>();
        if (reviewText == null || reviewText.isBlank())
            return files;

        Matcher m = JAVA_PATH.matcher(reviewText);
        while (m.find()) {
            files.add(m.group(1).replace('\\', '/').trim());
        }

        if (previousFiles != null) {
            for (ImplementationResult.FileDiff f : previousFiles) {
                String fileName = Paths.get(f.path()).getFileName().toString();
                String className = fileName.replaceAll("\\.[^.]+$", "");
                if (className.length() >= 5 && reviewText.contains(className)) {
                    files.add(f.path().replace('\\', '/'));
                }
            }
        }
        return files;
    }

    private boolean matchesAny(String stagedPath, Set<String> reviewPaths) {
        if (stagedPath == null)
            return false;
        String normalized = stagedPath.replace('\\', '/');
        for (String rp : reviewPaths) {
            if (normalized.equals(rp)
                    || normalized.endsWith(rp)
                    || rp.endsWith(normalized)) {
                return true;
            }
        }
        return false;
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
        if (!Files.isRegularFile(target)) {
            return "FILE DOES NOT EXIST: " + rel
                    + "\nIf the plan marked this file [NEW], this is expected — "
                    + "create it with write_file."
                    + "\nIf the plan marked this file [MODIFY], the path is wrong.";
        }
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

    private ImplementationResult partialResult(PipelineSession session,
            String summary, int turnCount, String stopReason,
            List<String> plannedFiles) {
        ImplementationResult built = buildResult(session.id(),
                summary == null ? "Implementer finished" : summary,
                turnCount, stopReason);

        int planned = plannedFiles != null ? plannedFiles.size() : 0;
        int staged = built.files() != null ? built.files().size() : 0;

        logBroadcaster.publish(LogEvent.success(
                "[IMPLEMENTER] " + staged + " file(s), +"
                        + built.totalAdditions() + "/-" + built.totalDeletions()
                        + " in " + turnCount + " turn(s)"
                        + (planned > 0 ? " · " + staged + "/" + planned + " planned" : "")));

        return built;
    }

    private ImplementationResult failedResult(String reason, int turnCount) {
        return new ImplementationResult(
                reason, List.of(), 0, 0, turnCount, "ERROR", LocalDateTime.now());
    }

    // ==================================================================
    // Prompt
    // ==================================================================

    private List<LlmClient.ToolDefinition> buildTools() {
        return List.of(
                new LlmClient.ToolDefinition(TOOL_SEARCH,
                        "Search the codebase using BM25. Matches class names, method "
                                + "names, and identifiers — NOT Java expressions or code snippets.",
                        schema(Map.of(
                                "query", prop("string", "Keyword query (class/method/identifier names)")),
                                List.of("query"))),

                new LlmClient.ToolDefinition(TOOL_READ,
                        "Read a file relative to the repo root. Returns FILE DOES NOT "
                                + "EXIST for missing files.",
                        schema(Map.of(
                                "path", prop("string", "File path relative to repo root")),
                                List.of("path"))),

                new LlmClient.ToolDefinition(TOOL_WRITE,
                        "Create a new file or fully overwrite an existing one. "
                                + "Required for [NEW] files.",
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
                        "Submit the completed implementation. Call this once every "
                                + "required file is staged.",
                        schema(Map.of(
                                "summary", prop("string", "One-sentence summary; note any incomplete files")),
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

    private Path pickWorkingRepoRoot() {
        RepositoryConfig repo = pickWorkingRepo();
        if (repo == null) {
            throw new IllegalStateException("No indexed repository available");
        }
        return Paths.get(repo.getPath()).toAbsolutePath().normalize();
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