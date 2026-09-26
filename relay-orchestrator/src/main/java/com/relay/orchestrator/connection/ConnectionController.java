package com.relay.orchestrator.connection;

import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.logging.TokenTrackerService;
import com.relay.orchestrator.pipeline.AnsweredQuestion;
import com.relay.orchestrator.pipeline.ClarificationLoopService;
import com.relay.orchestrator.pipeline.PipelineSession;
import com.relay.orchestrator.pipeline.PipelineStage;
import com.relay.orchestrator.pipeline.impl.ImplementationResult;
import com.relay.orchestrator.pipeline.impl.ImplementerService;
import com.relay.orchestrator.retrieval.ChunkRepository;
import com.relay.orchestrator.retrieval.RetrievalService;
import com.relay.orchestrator.retrieval.RetrievedChunk;
import com.relay.orchestrator.service.ClarificationResult;
import com.relay.orchestrator.service.IntentClarifierService;
import com.relay.orchestrator.tokens.TokenMetricsService;

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
            ImplementerService implementerService) {
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
            case BLOCKED, CAPPED, ERROR, IMPLEMENTATION_FAILED -> {
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
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }

        if (session.stage() != PipelineStage.READY_TO_IMPLEMENT) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Session is in stage " + session.stage()
                            + "; expected READY_TO_IMPLEMENT"));
        }

        executor.submit(() -> {
            try {
                ImplementationResult result = implementerService.implement(session);
                PipelineSession updated = session.withImplementation(result);
                loopService.save(updated);
            } catch (Exception e) {
                logBroadcaster.publish(LogEvent.error(
                        "[IMPLEMENTER] Implementation failed: " + e.getMessage()));
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "running",
                "sessionId", sessionId,
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
        }

        view.put("qaHistory", session.qaHistory());
        return view;
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
     * Bind form fields on top of the currently-loaded config so any field the
     * form does not submit (e.g. a masked token) retains its stored value.
     */
    @PostMapping("/connection/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @ModelAttribute("config") ConnectionConfig formConfig) {

        configService.save(formConfig);

        ConnectionConfig effective = configService.load()
                .orElseGet(ConnectionConfig::new);

        LlmConnectionChecker checker = copilotConnectionChecker;
        executor.submit(() -> checker.validate(effective));

        return ResponseEntity.accepted().body(Map.of(
                "status", "queued",
                "message", "Connection test started. Please watch the live log."));
    }
}