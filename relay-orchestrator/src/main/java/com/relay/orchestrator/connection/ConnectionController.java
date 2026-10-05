package com.relay.orchestrator.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.orchestrator.artifact.Artifact;
import com.relay.orchestrator.artifact.ArtifactKind;
import com.relay.orchestrator.artifact.Executor;
import com.relay.orchestrator.artifact.ExecutorRegistry;
import com.relay.orchestrator.artifact.ArtifactStore;
import com.relay.orchestrator.artifact.Producer;
import com.relay.orchestrator.artifact.ProducerRegistry;
import com.relay.orchestrator.build.BuildSystemDetector;
import com.relay.orchestrator.config.AppConfigManager;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.logging.TokenTrackerService;
import com.relay.orchestrator.pipeline.AnsweredQuestion;
import com.relay.orchestrator.pipeline.ClarificationLoopService;
import com.relay.orchestrator.pipeline.PipelineSession;
import com.relay.orchestrator.pipeline.PipelineStage;
import com.relay.orchestrator.pipeline.impl.ImplementationResult;
import com.relay.orchestrator.pipeline.impl.ApplyResult;
import com.relay.orchestrator.pipeline.impl.ApplyService;
import com.relay.orchestrator.pipeline.impl.ImplementerService;
import com.relay.orchestrator.pipeline.impl.ImplementerVerificationService;
import com.relay.orchestrator.pipeline.impl.WorkspaceSessionStore;
import com.relay.orchestrator.retrieval.ChunkRepository;
import com.relay.orchestrator.retrieval.RetrievalService;
import com.relay.orchestrator.retrieval.RetrievedChunk;
import com.relay.orchestrator.service.ClarificationResult;
import com.relay.orchestrator.service.IntentClarifierService;
import com.relay.orchestrator.test.TestRunnerService;
import com.relay.orchestrator.tokens.TokenMetricsService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
public class ConnectionController {

    private final ConnectionConfigService configService;
    private final ConnectionStatusService connectionStatusService;
    private final CopilotConnectionChecker copilotConnectionChecker;
    private final TokenTrackerService tokenTrackerService;
    private final IntentClarifierService intentClarifierService;
    private final LogBroadcaster logBroadcaster;
    private final TokenMetricsService tokenMetrics;
    private final ClarificationLoopService loopService;
    private final RetrievalService retrievalService;
    private final ChunkRepository chunkRepository;
    private final ImplementerService implementerService;
    private final ArtifactStore artifactStore;
    private final ProducerRegistry producerRegistry;
    private final ExecutorRegistry executorRegistry;
    private final ApplyService applyService;
    private final BuildSystemDetector buildSystemDetector;
    private final AppConfigManager appConfigManager;
    private final com.relay.orchestrator.test.TestRunnerService testRunnerService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final WorkspaceSessionStore workspaceSessionStore;
    private final ImplementerVerificationService verificationService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "connection-test");
        t.setDaemon(true);
        return t;
    });

    public ConnectionController(ConnectionConfigService configService,
            ConnectionStatusService connectionStatusService,
            CopilotConnectionChecker copilotConnectionChecker,
            TokenTrackerService tokenTrackerService,
            IntentClarifierService intentClarifierService,
            LogBroadcaster logBroadcaster,
            TokenMetricsService tokenMetrics,
            ClarificationLoopService loopService,
            RetrievalService retrievalService,
            ChunkRepository chunkRepository,
            ImplementerService implementerService,
            ArtifactStore artifactStore,
            ProducerRegistry producerRegistry,
            ExecutorRegistry executorRegistry,
            ApplyService applyService,
            BuildSystemDetector buildSystemDetector,
            AppConfigManager appConfigManager,
            TestRunnerService testRunnerService,
            ObjectMapper objectMapper,
            WorkspaceSessionStore workspaceSessionStore,
            ImplementerVerificationService verificationService) {
        this.configService = configService;
        this.connectionStatusService = connectionStatusService;
        this.copilotConnectionChecker = copilotConnectionChecker;
        this.tokenTrackerService = tokenTrackerService;
        this.intentClarifierService = intentClarifierService;
        this.logBroadcaster = logBroadcaster;
        this.tokenMetrics = tokenMetrics;
        this.loopService = loopService;
        this.retrievalService = retrievalService;
        this.chunkRepository = chunkRepository;
        this.implementerService = implementerService;
        this.artifactStore = artifactStore;
        this.producerRegistry = producerRegistry;
        this.executorRegistry = executorRegistry;
        this.applyService = applyService;
        this.buildSystemDetector = buildSystemDetector;
        this.appConfigManager = appConfigManager;
        this.objectMapper = new ObjectMapper();
        this.testRunnerService = testRunnerService;
        this.workspaceSessionStore = workspaceSessionStore;
        this.verificationService = verificationService;
    }

    @ModelAttribute("config")
    public ConnectionConfig currentConfig() {
        return configService.load().orElseGet(ConnectionConfig::new);
    }

    @ModelAttribute("connectionStatus")
    public ConnectionStatus currentStatus() {
        return connectionStatusService.getLatest();
    }

    @ModelAttribute("providerOptions")
    public ConnectionConfig.Provider[] providerOptions() {
        return ConnectionConfig.Provider.values();
    }

    @ModelAttribute("pipelineStages")
    public List<Map<String, String>> pipelineStagesData() {
        PipelineStage current = PipelineStage.NEW;
        String lastId = null;
        try {
            var recent = loopService.recent(1);
            if (!recent.isEmpty()) {
                current = recent.get(0).stage();
                lastId = recent.get(0).id();
            }
        } catch (Exception ignored) {
            // no sessions yet
        }

        String analysis = "queued";
        String decision = "queued";
        String compile = "queued";
        String test = "queued";

        switch (current) {
            case NEW -> analysis = "active";
            case AWAITING_ANSWERS -> {
                analysis = "active";
                decision = "active";
            }
            case READY_TO_IMPLEMENT -> {
                analysis = "done";
                decision = "done";
            }
            case IMPLEMENTATION_RUNNING -> {
                analysis = "done";
                decision = "done";
                compile = "active";
            }
            case IMPLEMENTATION_READY -> {
                analysis = "done";
                decision = "done";
                compile = "done";
                test = "active";
            }
            case BLOCKED, CAPPED, ERROR, IMPLEMENTATION_FAILED, COMPILE_FAILED -> {
                analysis = "done";
                decision = "done";
            }
        }

        // Every stage in a session navigates to the same page + session id.
        // The pages themselves decide what to render based on stage.
        String pipelineHref = lastId != null ? "/?session=" + lastId : "/";
        String previewHref = lastId != null ? "/code-preview?session=" + lastId : "/code-preview";

        List<Map<String, String>> stages = new ArrayList<>();
        stages.add(Map.of("name", "Analysis", "state", analysis, "icon", "✓", "href", pipelineHref));
        stages.add(Map.of("name", "Decision", "state", decision, "icon", "✓", "href", pipelineHref));
        stages.add(Map.of("name", "Compile", "state", compile, "icon", "◉", "href", pipelineHref));
        // Test stage lands on the diff view once implementation is ready.
        String testHref = "active".equals(test) ? previewHref : pipelineHref;
        stages.add(Map.of("name", "Test", "state", test, "icon", "•", "href", testHref));
        return stages;
    }

    @GetMapping("/")
    public String home(Model model) {
        return pipelineStages(model);
    }

    @PostMapping("/orchestrate/run")
    @ResponseBody
    public ResponseEntity<?> handlePipelineExecutionRun(@RequestParam String storyInput) {
        if (storyInput == null || storyInput.isBlank()) {
            logBroadcaster.publish(LogEvent.warn("Pipeline run rejected: empty story input"));
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "rejected", "message", "Story input is required."));
        }

        try {
            ClarificationResult result = intentClarifierService.clarify(storyInput);

            String status = switch (result.state()) {
                case READY -> "ready";
                case NEEDS_INPUT -> "needs_input";
                case BLOCKED -> "blocked";
            };

            logBroadcaster.publish(LogEvent.info(
                    "[CLARIFIER] Result: " + status + " - " + result.summary()));

            return ResponseEntity.ok(Map.of(
                    "status", status,
                    "summary", result.summary(),
                    "confidence", result.confidence().name(),
                    "complexity", result.estimatedComplexity(),
                    "risks", result.riskNotes(),
                    "assumptions", result.assumptionsMade(),
                    "questions", result.questions()));
        } catch (Exception e) {
            logBroadcaster.publish(LogEvent.error(
                    "Pipeline orchestration failed: " + e.getMessage()));
            return ResponseEntity.status(500).body(Map.of(
                    "status", "failed",
                    "message", "Clarifier failed. You can retry.",
                    "error", e.getMessage()));
        }
    }

    @GetMapping("/pipeline/sessions")
    @ResponseBody
    public ResponseEntity<?> listSessions(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(
                loopService.recent(limit).stream()
                        .map(this::toSessionView)
                        .toList());
    }

    @PostMapping("/pipeline/clarify")
    @ResponseBody
    public ResponseEntity<?> startClarification(@RequestBody Map<String, String> body) {
        String story = body.get("story");
        if (story == null || story.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "story is required"));
        }
        try {
            PipelineSession session = loopService.start(story);
            return ResponseEntity.ok(toSessionView(session));
        } catch (Exception e) {
            logBroadcaster.publish(LogEvent.error("Pipeline start failed: " + e.getMessage()));
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/pipeline/clarify/resume")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> resumeClarification(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        }
        String additionalContext = (String) body.getOrDefault("additionalContext", "");

        List<AnsweredQuestion> answers = new ArrayList<>();
        Object rawAnswers = body.get("answers");
        if (rawAnswers instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String qid = String.valueOf(m.get("questionId"));
                    String ans = String.valueOf(m.get("answer"));
                    answers.add(new AnsweredQuestion(qid, "", "", ans));
                }
            }
        }

        try {
            PipelineSession session = loopService.resume(sessionId, answers, additionalContext);
            return ResponseEntity.ok(toSessionView(session));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logBroadcaster.publish(LogEvent.error("Resume failed: " + e.getMessage()));
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pipeline/session/{id}")
    @ResponseBody
    public ResponseEntity<?> getSession(@PathVariable String id) {
        try {
            return ResponseEntity.ok(toSessionView(loopService.get(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Phase 2 — Implementer
    // ------------------------------------------------------------------

    @PostMapping("/pipeline/implement")
    @ResponseBody
    public ResponseEntity<?> startImplementation(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId is required"));
        }

        PipelineSession session;
        try {
            session = loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }

        // Stage guard
        PipelineStage stage = session.stage();
        if (stage != PipelineStage.READY_TO_IMPLEMENT
                && stage != PipelineStage.IMPLEMENTATION_READY
                && stage != PipelineStage.IMPLEMENTATION_FAILED) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session is in stage " + stage
                            + "; cannot run implementer"));
        }

        // Require an approved PLAN artifact
        Artifact plan = artifactStore
                .findBySessionAndKind(sessionId, ArtifactKind.PLAN)
                .stream()
                .findFirst()
                .orElse(null);

        if (plan == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No plan found. Generate and approve a plan first."));
        }
        if (!plan.isApproved() && !plan.isExecuted()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Plan is " + plan.status()
                            + "; approve it before running the implementer."));
        }

        if (!executorRegistry.has(ArtifactKind.PLAN)) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "No executor registered for PLAN"));
        }

        Executor planExecutor = executorRegistry.forKind(ArtifactKind.PLAN);
        final Artifact planRef = plan;
        final PipelineSession sessionRef = session;

        // Mark as RUNNING before submit so the poll doesn't misread the state
        PipelineSession running = session.with(
                PipelineStage.IMPLEMENTATION_RUNNING, session.lastResult());
        loopService.save(running);

        final PipelineSession runningRef = running;

        executor.submit(() -> {
            try {
                planExecutor.execute(planRef, runningRef);
            } catch (Exception e) {
                logBroadcaster.publish(LogEvent.error(
                        "[EXECUTOR] Unexpected failure: " + e.getMessage()));
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "running",
                "sessionId", sessionId,
                "artifactId", plan.id(),
                "message", "Implementer started. Watch the live log."));
    }

    @GetMapping("/pipeline/session/{id}/diff")
    @ResponseBody
    public ResponseEntity<?> getSessionDiff(@PathVariable String id) {
        try {
            PipelineSession session = loopService.get(id);
            if (session.implementationResult() == null) {
                return ResponseEntity.ok(Map.of("status", "none", "files", List.of()));
            }
            return ResponseEntity.ok(session.implementationResult());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // View rendering
    // ------------------------------------------------------------------

    private Map<String, Object> toSessionView(PipelineSession session) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("sessionId", session.id());
        view.put("stage", session.stage().name());
        view.put("turnCount", session.turnCount());
        view.put("maxTurns", session.maxTurns());
        view.put("originalStory", session.originalStory());
        view.put("canAskAgain", session.canAskAgain());
        view.put("createdAt", session.createdAt().toString());
        view.put("updatedAt", session.updatedAt().toString());

        if (session.lastResult() != null) {
            view.put("state", session.lastResult().state().name());
            view.put("confidence", session.lastResult().confidence().name());
            view.put("summary", session.lastResult().summary());
            view.put("complexity", session.lastResult().estimatedComplexity());
            view.put("questions", session.lastResult().questions());
            view.put("riskNotes", session.lastResult().riskNotes());
            view.put("assumptionsMade", session.lastResult().assumptionsMade());
            view.put("recommendedIntent",
                    session.lastResult().effectiveIntent().name());
            view.put("intentConfidence",
                    session.lastResult().effectiveIntentConfidence().name());
        }

        if (session.implementationResult() != null
                && session.implementationResult().files() != null) {
            view.put("changedFiles",
                    session.implementationResult().files().stream()
                            .map(ImplementationResult.FileDiff::path)
                            .toList());
        }

        view.put("qaHistory", session.qaHistory());
        return view;
    }

    /**
     * FIX fast path. Dispatches directly to the no-plan implementer
     * overload — the clarifier's summary + Q&A are the directive.
     */
    @PostMapping("/pipeline/{sessionId}/fix")
    @ResponseBody
    public ResponseEntity<?> startFix(@PathVariable String sessionId) {
        PipelineSession session;
        try {
            session = loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }

        // Safety: restore any dirty state a prior crash may have left behind.
        // No-op on clean repos (undo returns "No backups found").
        ApplyResult preUndo = applyService.undo(session);
        logBroadcaster.publish(LogEvent.info(
                "[FIX] Pre-run undo: " + preUndo.message()));

        if (session.stage() != PipelineStage.READY_TO_IMPLEMENT) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session is in stage " + session.stage()
                            + "; expected READY_TO_IMPLEMENT"));
        }
        if (session.lastResult() == null
                || session.lastResult().effectiveIntent() != ClarificationResult.Intent.FIX) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session intent is not FIX"));
        }

        PipelineSession running = session.with(
                PipelineStage.IMPLEMENTATION_RUNNING, session.lastResult());
        loopService.save(running);

        executor.submit(() -> {
            try {
                ImplementationResult raw = implementerService.implement(running);

                if (raw.files().isEmpty()) {
                    PipelineSession updated = running.with(
                            PipelineStage.APPLIED, running.lastResult());
                    loopService.save(updated);
                    logBroadcaster.publish(LogEvent.info(
                            "[FIX] No changes needed — the bug appears to be fixed already"));
                    return;
                }

                ImplementerVerificationService.VerificationOutcome outcome = verificationService.verify(running, raw);

                if (outcome.status() == ImplementerVerificationService.VerificationOutcome.Status.FAILED
                        || outcome.status() == ImplementerVerificationService.VerificationOutcome.Status.ERROR) {
                    String errors = outcome.compileErrors() != null
                            ? outcome.compileErrors()
                            : outcome.message();
                    PipelineSession updated = running.withCompileErrors(errors);
                    loopService.save(updated);
                    logBroadcaster.publish(LogEvent.error(
                            "[FIX] Compile gate failed: " + outcome.message()));
                    return;
                }

                ImplementationResult finalResult = outcome.result() != null ? outcome.result() : raw;
                PipelineSession updated = running.withImplementation(finalResult);
                loopService.save(updated);
                logBroadcaster.publish(LogEvent.success(
                        "[FIX] " + finalResult.files().size() + " file(s), +"
                                + finalResult.totalAdditions() + "/-"
                                + finalResult.totalDeletions()));
            } catch (Exception e) {
                logBroadcaster.publish(LogEvent.error(
                        "[FIX] Failed: " + e.getMessage()));
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "running",
                "sessionId", sessionId,
                "message", "Fix started. Watch the live log."));
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        ConnectionConfig config = configService.load().orElseGet(ConnectionConfig::new);
        model.addAttribute("activeTab", "settings");
        model.addAttribute("viewContent", "settings");
        model.addAttribute("config", config);
        return "layout";
    }

    @GetMapping("/pipeline/stages")
    public String pipelineStages(Model model) {
        ConnectionConfig config = configService.load().orElseGet(ConnectionConfig::new);
        model.addAttribute("activeTab", "pipeline");
        model.addAttribute("viewContent", "pipeline-stages");
        model.addAttribute("config", config);
        return "layout";
    }

    @GetMapping("/workspace/topology")
    public String workspaceTopology(Model model) {
        ConnectionConfig config = configService.load().orElseGet(ConnectionConfig::new);
        model.addAttribute("activeTab", "topology");
        model.addAttribute("viewContent", "topology");
        model.addAttribute("config", config);
        return "layout";
    }

    @GetMapping("/token-tracker")
    public String tokenTracker(Model model) {
        ConnectionConfig config = configService.load().orElseGet(ConnectionConfig::new);
        model.addAttribute("activeTab", "token-tracker");
        model.addAttribute("viewContent", "token-tracker");
        model.addAttribute("config", config);

        model.addAttribute("totalCost", tokenTrackerService.getTotalCost());
        model.addAttribute("totalTokens", tokenTrackerService.getTotalTokens());
        model.addAttribute("transactions", tokenTrackerService.getTransactions());

        model.addAttribute("persistentRecords", tokenMetrics.totalRecords());
        model.addAttribute("persistentTokensToday", tokenMetrics.tokensToday());
        model.addAttribute("persistentCostToday", tokenMetrics.costToday());
        model.addAttribute("persistentRecent", tokenMetrics.recent(20));
        model.addAttribute("cacheReadsToday", tokenMetrics.cacheReadsToday());
        model.addAttribute("cacheWritesToday", tokenMetrics.cacheWritesToday());
        model.addAttribute("inputTokensToday", tokenMetrics.inputTokensToday());
        model.addAttribute("outputTokensToday", tokenMetrics.outputTokensToday());
        model.addAttribute("totalInputTokens", tokenMetrics.totalInputTokens());
        model.addAttribute("totalOutputTokens", tokenMetrics.totalOutputTokens());
        model.addAttribute("callsToday", tokenMetrics.callsToday());

        model.addAttribute("persistentRecords", tokenMetrics.totalRecords());
        model.addAttribute("persistentRecent", tokenMetrics.recent(20));
        model.addAttribute("cacheReadsToday", tokenMetrics.cacheReadsToday());
        model.addAttribute("cacheWritesToday", tokenMetrics.cacheWritesToday());

        model.addAttribute("approxPremiumRequestsToday",
                tokenMetrics.approximatePremiumRequestsToday());
        model.addAttribute("approxPremiumRequestsAllTime",
                tokenMetrics.approximatePremiumRequestsAllTime());

        return "layout";
    }

    @GetMapping("/token-tracker/table-fragment")
    public String tokenTrackerTableFragment(Model model) {
        model.addAttribute("transactions", tokenTrackerService.getTransactions());
        model.addAttribute("totalCost", tokenTrackerService.getTotalCost());
        model.addAttribute("totalTokens", tokenTrackerService.getTotalTokens());
        return "token-tracker :: ledger-rows";
    }

    @GetMapping("/logs")
    public String terminalLogs(Model model) {
        ConnectionConfig config = configService.load().orElseGet(ConnectionConfig::new);
        model.addAttribute("activeTab", "logs");
        model.addAttribute("viewContent", "logs");
        model.addAttribute("config", config);
        return "layout";
    }

    @GetMapping("/code-preview")
    public String codePreview(Model model) {
        ConnectionConfig config = configService.load().orElseGet(ConnectionConfig::new);
        model.addAttribute("activeTab", "code-preview");
        model.addAttribute("viewContent", "code-preview");
        model.addAttribute("config", config);
        return "layout";
    }

    @GetMapping("/support")
    public String support(Model model) {
        ConnectionConfig config = configService.load().orElseGet(ConnectionConfig::new);
        model.addAttribute("activeTab", "support");
        model.addAttribute("viewContent", "support");
        model.addAttribute("config", config);
        return "layout";
    }

    /**
     * TEMPORARY debug endpoint — inspect retrieval. With retrieval stubbed,
     * this returns empty results; kept for the upcoming BM25 migration.
     */
    @GetMapping("/debug/retrieve")
    @ResponseBody
    public ResponseEntity<?> debugRetrieve(@RequestParam String story) {
        try {
            long start = System.currentTimeMillis();
            int totalInDb = chunkRepository.countAll();
            List<RetrievedChunk> top10 = retrievalService.retrieveForStory(story, 10, 0.0f, 100_000);
            long elapsed = System.currentTimeMillis() - start;

            List<Map<String, Object>> results = new ArrayList<>();
            for (RetrievedChunk rc : top10) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("similarity", (double) rc.similarity());
                m.put("type", rc.chunk().chunkType());
                m.put("qualifiedName", rc.chunk().qualifiedName());
                m.put("filePath", rc.chunk().filePath());
                results.add(m);
            }

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("story", story);
            body.put("totalChunksInDb", totalInDb);
            body.put("elapsedMs", elapsed);
            body.put("top10", results);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * Test Connection must be side-effect-free. Persisting here rewrote
     * config.yml on every click — the Phase 0 split-brain bug.
     * Users save explicitly via POST /settings/save.
     */
    @PostMapping("/connection/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @ModelAttribute("config") ConnectionConfig formConfig) {

        ConnectionConfig effective = mergeOverStored(formConfig);
        executor.submit(() -> copilotConnectionChecker.validate(effective));

        return ResponseEntity.accepted().body(Map.of(
                "status", "queued",
                "message", "Connection test started. Please watch the live log."));
    }

    /** Fill in any blank/omitted field from the stored config before probing. */
    private ConnectionConfig mergeOverStored(ConnectionConfig form) {
        ConnectionConfig stored = configService.load().orElseGet(ConnectionConfig::new);
        if (form.getGithubToken() == null || form.getGithubToken().isBlank())
            form.setGithubToken(stored.getGithubToken());
        if (form.getModel() == null || form.getModel().isBlank())
            form.setModel(stored.getModel());
        if (form.getWorkspaceDir() == null || form.getWorkspaceDir().isBlank())
            form.setWorkspaceDir(stored.getWorkspaceDir());
        if (form.getRequestBudget() <= 0)
            form.setRequestBudget(stored.getRequestBudget());
        return form;
    }

    @PostMapping("/settings/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveSettings(
            @ModelAttribute("config") ConnectionConfig formConfig) {
        configService.save(formConfig);
        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    // ==================================================================
    // ARTIFACT ENDPOINTS
    // ==================================================================

    /**
     * Generate an artifact of the given kind for a session.
     * Path param `kind` accepts: plan, design, rca, review (case-insensitive).
     */
    @PostMapping("/pipeline/{sessionId}/artifact/{kind}")
    @ResponseBody
    public ResponseEntity<?> generateArtifact(@PathVariable String sessionId,
            @PathVariable String kind) {

        PipelineSession session;
        try {
            session = loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }

        ArtifactKind artifactKind;
        try {
            artifactKind = ArtifactKind.valueOf(kind.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown artifact kind: " + kind));
        }

        if (!producerRegistry.has(artifactKind)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No producer registered for " + artifactKind));
        }

        try {
            Producer producer = producerRegistry.forKind(artifactKind);
            Artifact artifact = producer.produce(session);
            return ResponseEntity.ok(toArtifactView(artifact));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logBroadcaster.publish(LogEvent.error(
                    "Artifact generation failed: " + e.getMessage()));
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /** List all artifacts for a session, oldest first. */
    @GetMapping("/pipeline/{sessionId}/artifacts")
    @ResponseBody
    public ResponseEntity<?> listArtifacts(@PathVariable String sessionId) {
        try {
            loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }
        return ResponseEntity.ok(
                artifactStore.findBySession(sessionId).stream()
                        .map(this::toArtifactView)
                        .toList());
    }

    /** Fetch a single artifact by id. */
    @GetMapping("/artifact/{id}")
    @ResponseBody
    public ResponseEntity<?> getArtifact(@PathVariable String id) {
        return artifactStore.find(id)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(toArtifactView(a)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "Artifact not found")));
    }

    /** Approve a DRAFT artifact. Only DRAFT → APPROVED transitions succeed. */
    @PostMapping("/artifact/{id}/approve")
    @ResponseBody
    public ResponseEntity<?> approveArtifact(@PathVariable String id) {
        boolean ok = artifactStore.approve(id, "user");
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Artifact not found or not in DRAFT status"));
        }
        return artifactStore.find(id)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(toArtifactView(a)))
                .orElseGet(() -> ResponseEntity.status(500)
                        .body(Map.of("error", "state desync")));
    }

    /** Reject a DRAFT artifact. Only DRAFT → REJECTED transitions succeed. */
    @PostMapping("/artifact/{id}/reject")
    @ResponseBody
    public ResponseEntity<?> rejectArtifact(@PathVariable String id) {
        boolean ok = artifactStore.reject(id);
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Artifact not found or not in DRAFT status"));
        }
        return artifactStore.find(id)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(toArtifactView(a)))
                .orElseGet(() -> ResponseEntity.status(500)
                        .body(Map.of("error", "state desync")));
    }

    /** Convert an Artifact record to a JSON-friendly view. */
    private Map<String, Object> toArtifactView(Artifact a) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", a.id());
        view.put("shortId", a.shortId());
        view.put("sessionId", a.sessionId());
        view.put("kind", a.kind().name());
        view.put("status", a.status().name());
        view.put("content", a.content());
        view.put("createdBy", a.createdBy());
        view.put("approvedBy", a.approvedBy());
        view.put("createdAt", a.createdAt().toString());
        view.put("approvedAt", a.approvedAt() != null ? a.approvedAt().toString() : null);
        view.put("verdict", a.verdict());
        return view;
    }

    @PostMapping("/pipeline/{sessionId}/reimplement")
    @ResponseBody
    public ResponseEntity<?> reimplement(@PathVariable String sessionId) {

        PipelineSession session;
        try {
            session = loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }

        // Safety: restore any dirty state a prior crash may have left behind.
        ApplyResult preUndo = applyService.undo(session);
        logBroadcaster.publish(LogEvent.info(
                "[REIMPL] Pre-run undo: " + preUndo.message()));

        Artifact plan = artifactStore
                .findBySessionAndKind(sessionId, ArtifactKind.PLAN)
                .stream().findFirst().orElse(null);

        Artifact review = artifactStore
                .findBySessionAndKind(sessionId, ArtifactKind.REVIEW)
                .stream().findFirst().orElse(null);

        if (review == null) {
            Artifact testRun = artifactStore
                    .findBySessionAndKind(sessionId, ArtifactKind.TEST_RUN)
                    .stream().reduce((a, b) -> b).orElse(null); // latest

            if (testRun != null) {
                // Synthesize a review artifact from the test result.
                String feedback = buildTestFailureFeedback(testRun.content());
                String syntheticId = artifactStore.create(
                        sessionId, ArtifactKind.REVIEW, feedback,
                        "REQUEST_CHANGES", "system");
                review = artifactStore.find(syntheticId).orElse(null);
            }
        }
        if (review == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No review available. Generate a review first."));
        }

        if ("APPROVE".equalsIgnoreCase(review.verdict())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Review verdict is APPROVE; nothing to re-implement."));
        }

        // Delete the stale review — the diff is about to change
        artifactStore.delete(sessionId, ArtifactKind.REVIEW);

        final String feedback = review.content();

        // FIX path — no approved PLAN artifact. Re-run the no-plan
        // implementer with the review feedback as the directive.
        boolean hasApprovedPlan = plan != null
                && (plan.isApproved() || plan.isExecuted());

        // The file on disk may or may not be pristine. Two things can put
        // the previous diff on disk:
        // (a) session.stage() == APPLIED (runTests auto-applied it)
        // (b) an earlier reimplement left the mangled diff behind
        // In both cases, undo before re-running. applyService.undo reads
        // the latest backup from .relay-backup and restores the original.
        // If no backup exists, the undo fails — but that just means the
        // file is already pristine, so we log and continue.
        logBroadcaster.publish(LogEvent.info(
                "[REIMPL] Session stage before undo: " + session.stage()));

        ApplyResult undoResult = applyService.undo(session);
        logBroadcaster.publish(LogEvent.info(
                "[REIMPL] Undo result: ok=" + undoResult.ok()
                        + " files=" + undoResult.fileCount()
                        + " msg=" + undoResult.message()));

        if (undoResult.ok()) {
            // Restore the pipeline stage so downstream code knows the diff
            // is no longer on disk.
            if (session.stage() == PipelineStage.APPLIED) {
                session = session.with(PipelineStage.IMPLEMENTATION_READY, session.lastResult());
                loopService.save(session);
            }
            logBroadcaster.publish(LogEvent.info(
                    "[REIMPL] Reverted " + undoResult.fileCount()
                            + " file(s) to pristine state before re-implement"));
        } else {
            logBroadcaster.publish(LogEvent.info(
                    "[REIMPL] No backup to undo — assuming pristine, proceeding"));
        }

        if (!hasApprovedPlan) {
            PipelineSession running = session.with(
                    PipelineStage.IMPLEMENTATION_RUNNING, session.lastResult());
            loopService.save(running);
            final PipelineSession runningRef = running;

            executor.submit(() -> {
                try {
                    ImplementationResult raw = implementerService.implement(
                            runningRef, "", feedback);

                    ImplementerVerificationService.VerificationOutcome outcome = verificationService.verify(runningRef,
                            raw);

                    if (outcome.status() == ImplementerVerificationService.VerificationOutcome.Status.FAILED) {
                        PipelineSession failed = runningRef.withCompileErrors(outcome.compileErrors());
                        loopService.save(failed);
                        logBroadcaster.publish(LogEvent.error(
                                "[REIMPL] Compile failed after retry — see COMPILE_FAILED"));
                        return;
                    }

                    ImplementationResult finalResult = outcome.result() != null ? outcome.result() : raw;
                    PipelineSession updated = runningRef.withImplementation(finalResult);
                    loopService.save(updated);
                    logBroadcaster.publish(LogEvent.success(
                            "[REIMPL] Re-implemented: " + finalResult.files().size()
                                    + " file(s)"));
                } catch (Exception e) {
                    logBroadcaster.publish(LogEvent.error(
                            "[REIMPL] Failed: " + e.getMessage()));
                }
            });

            return ResponseEntity.accepted().body(Map.of(
                    "status", "running",
                    "sessionId", sessionId,
                    "message", "Re-implementation started. Watch the live log."));
        }

        // PLAN path
        if (!executorRegistry.has(ArtifactKind.PLAN)) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "No executor registered for PLAN"));
        }

        if (!(executorRegistry
                .forKind(ArtifactKind.PLAN) instanceof com.relay.orchestrator.artifact.PlanExecutor planExecutor)) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "PLAN executor does not support feedback"));
        }

        final Artifact planRef = plan;
        PipelineSession running = session.with(
                PipelineStage.IMPLEMENTATION_RUNNING, session.lastResult());
        loopService.save(running);

        final PipelineSession runningRef = running;

        executor.submit(() -> {
            try {
                planExecutor.executeWithFeedback(planRef, runningRef, feedback);
            } catch (Exception e) {
                logBroadcaster.publish(LogEvent.error(
                        "[EXECUTOR] Re-implementation failed: " + e.getMessage()));
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "running",
                "sessionId", sessionId,
                "message", "Re-implementation started. Watch the live log."));
    }

    @GetMapping("/artifact/{id}/download")
    public ResponseEntity<?> downloadArtifact(@PathVariable String id) {
        return artifactStore.find(id)
                .<ResponseEntity<?>>map(a -> {
                    String filename = a.kind().name().toLowerCase()
                            + "-" + a.shortId() + ".md";
                    byte[] bytes = a.content().getBytes(StandardCharsets.UTF_8);

                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                            .header("Content-Disposition",
                                    "attachment; filename=\"" + filename + "\"")
                            .body(bytes);
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "Artifact not found")));
    }

    @PostMapping("/pipeline/{sessionId}/apply")
    @ResponseBody
    public ResponseEntity<?> applyChanges(@PathVariable String sessionId) {
        PipelineSession session;
        try {
            session = loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }

        if (session.stage() != PipelineStage.IMPLEMENTATION_READY) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session is in stage " + session.stage()
                            + "; expected IMPLEMENTATION_READY"));
        }

        ApplyResult result = applyService.apply(session);
        if (!result.ok()) {
            logBroadcaster.publish(LogEvent.error("[APPLY] " + result.message()));
            return ResponseEntity.badRequest().body(Map.of("error", result.message()));
        }

        PipelineSession updated = session.with(PipelineStage.APPLIED, session.lastResult());
        loopService.save(updated);

        logBroadcaster.publish(LogEvent.success(
                "[APPLY] " + result.fileCount() + " file(s) written to disk"));

        return ResponseEntity.ok(Map.of(
                "status", "applied",
                "fileCount", result.fileCount(),
                "backupPath", result.backupPath()));
    }

    @PostMapping("/pipeline/{sessionId}/undo")
    @ResponseBody
    public ResponseEntity<?> undoChanges(@PathVariable String sessionId) {
        PipelineSession session;
        try {
            session = loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }

        if (session.stage() != PipelineStage.APPLIED) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session is in stage " + session.stage()
                            + "; expected APPLIED"));
        }

        ApplyResult result = applyService.undo(session);
        if (!result.ok()) {
            return ResponseEntity.badRequest().body(Map.of("error", result.message()));
        }

        PipelineSession updated = session.with(PipelineStage.IMPLEMENTATION_READY, session.lastResult());
        loopService.save(updated);

        // The test result is no longer valid — the disk state it was run against
        // has been rolled back. Delete it so the UI doesn't show a stale PASSED.
        artifactStore.delete(sessionId, ArtifactKind.TEST_RUN);
        logBroadcaster.publish(LogEvent.warn(
                "[UNDO] " + result.fileCount() + " file(s) restored from backup"));

        return ResponseEntity.ok(Map.of(
                "status", "undone",
                "fileCount", result.fileCount(),
                "backupPath", result.backupPath()));
    }

    @GetMapping("/debug/detect-build")
    @ResponseBody
    public ResponseEntity<?> debugDetectBuild() {
        try {
            var repos = appConfigManager.getRepositories();
            if (repos.isEmpty()) {
                return ResponseEntity.ok(Map.of("error", "No repos configured"));
            }
            var repo = repos.get(0);
            var path = java.nio.file.Paths.get(repo.getPath()).toAbsolutePath().normalize();
            var detection = buildSystemDetector.detect(path);
            return ResponseEntity.ok(Map.of(
                    "repoId", repo.getId(),
                    "path", path.toString(),
                    "system", detection.getSystem().getId(),
                    "executable", detection.getExecutable() == null ? "" : detection.getExecutable(),
                    "compileArgs", detection.getCompileArgs() == null ? List.of() : detection.getCompileArgs(),
                    "testArgs", detection.getTestArgs() == null ? List.of() : detection.getTestArgs(),
                    "detected", detection.isDetected()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    @PostMapping("/pipeline/{sessionId}/run-tests")
    @ResponseBody
    public ResponseEntity<?> runTests(@PathVariable String sessionId) {
        PipelineSession session;
        try {
            session = loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }

        if (session.implementationResult() == null
                || session.implementationResult().files().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session has no diff to test"));
        }

        // Test runner needs files on disk. Auto-apply if not already APPLIED.
        if (session.stage() == PipelineStage.IMPLEMENTATION_READY) {
            ApplyResult ar = applyService.apply(session);
            if (!ar.ok()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Could not apply diff: " + ar.message()));
            }
            session = session.with(PipelineStage.APPLIED, session.lastResult());
            loopService.save(session);
            logBroadcaster.publish(LogEvent.info(
                    "[TESTS] Diff applied to disk before test run"));
        } else if (session.stage() != PipelineStage.APPLIED) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session is in stage " + session.stage()
                            + "; expected IMPLEMENTATION_READY or APPLIED"));
        }

        com.relay.orchestrator.test.TestRunResult result = testRunnerService.run();

        String artId;
        try {
            String json = objectMapper.writeValueAsString(result);
            artId = artifactStore.create(sessionId, ArtifactKind.TEST_RUN,
                    json, null, "system");
        } catch (Exception e) {
            logBroadcaster.publish(LogEvent.error(
                    "Failed to persist TEST_RUN artifact: " + e.getMessage()));
            artId = null;
        }

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("artifactId", artId);
        body.put("result", result);
        return ResponseEntity.ok(body);
    }

    private String buildTestFailureFeedback(String testRunJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(testRunJson);
            StringBuilder sb = new StringBuilder();
            sb.append("# Test failure report\n\n");
            sb.append("**Status:** ").append(node.path("status").asText("UNKNOWN")).append("\n\n");

            if ("COMPILE_FAILED".equals(node.path("status").asText())) {
                sb.append("## Compilation error\n\n");
                sb.append("The previous implementation did not compile. "
                        + "Maven output (tail):\n\n```\n");
                String stdout = node.path("stdoutTail").asText("");
                if (stdout.length() > 3000) {
                    stdout = stdout.substring(stdout.length() - 3000);
                }
                sb.append(stdout).append("\n```\n\n");
                sb.append("Fix every compilation error above before making further "
                        + "changes. Verify the file parses cleanly with `mvn compile` "
                        + "before calling submit_plan.");
            } else {
                sb.append("**Passed:** ").append(node.path("passedTests").asInt()).append("\n");
                sb.append("**Failed:** ").append(node.path("failedTests").asInt()).append("\n\n");
                sb.append("## Failures\n\n");
                for (com.fasterxml.jackson.databind.JsonNode f : node.path("failures")) {
                    if ("SKIPPED".equals(f.path("kind").asText()))
                        continue;
                    sb.append("- `").append(f.path("className").asText())
                            .append(".").append(f.path("testName").asText()).append("` — ")
                            .append(f.path("message").asText("")).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Test failures detected but could not be parsed: " + e.getMessage();
        }
    }

    @PostMapping("/pipeline/{sessionId}/files/remove")
    @ResponseBody
    public ResponseEntity<?> removeFile(@PathVariable String sessionId,
            @RequestParam String path) {
        PipelineSession session;
        try {
            session = loopService.get(sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }

        boolean removed = workspaceSessionStore.remove(sessionId, path);
        if (!removed) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "File not found in staged changes: " + path));
        }

        // Rebuild the implementationResult from the updated workspace
        if (session.implementationResult() != null
                && session.implementationResult().files() != null) {
            List<ImplementationResult.FileDiff> remaining = session.implementationResult().files().stream()
                    .filter(f -> !f.path().equals(path))
                    .toList();
            ImplementationResult updated = new ImplementationResult(
                    session.implementationResult().summary(),
                    remaining,
                    session.implementationResult().totalAdditions(),
                    session.implementationResult().totalDeletions(),
                    session.implementationResult().turnCount(),
                    session.implementationResult().stopReason(),
                    session.implementationResult().completedAt());
            PipelineSession next = session.withImplementation(updated);
            loopService.save(next);
        }

        logBroadcaster.publish(LogEvent.warn("[REIMPL] User removed file: " + path));

        return ResponseEntity.ok(Map.of("status", "removed", "path", path));
    }
}