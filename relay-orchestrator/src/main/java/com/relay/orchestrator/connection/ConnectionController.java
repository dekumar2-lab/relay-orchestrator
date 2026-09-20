package com.relay.orchestrator.connection;

import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.logging.TokenTrackerService;
import com.relay.orchestrator.service.IntentClarifierService;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
public class ConnectionController {

    private final ConnectionConfigService configService;
    private final ConnectionStatusService connectionStatusService;
    private final CopilotConnectionChecker copilotConnectionChecker;
    private final ClaudeApiConnectionChecker claudeApiConnectionChecker;
    private final TokenTrackerService tokenTrackerService;
    private final IntentClarifierService intentClarifierService;
    private final LogBroadcaster logBroadcaster;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "connection-test");
        t.setDaemon(true);
        return t;
    });

    public ConnectionController(ConnectionConfigService configService,
            ConnectionStatusService connectionStatusService,
            CopilotConnectionChecker copilotConnectionChecker,
            ClaudeApiConnectionChecker claudeApiConnectionChecker,
            TokenTrackerService tokenTrackerService,
            IntentClarifierService intentClarifierService,
            LogBroadcaster logBroadcaster) {
        this.configService = configService;
        this.connectionStatusService = connectionStatusService;
        this.copilotConnectionChecker = copilotConnectionChecker;
        this.claudeApiConnectionChecker = claudeApiConnectionChecker;
        this.tokenTrackerService = tokenTrackerService;
        this.intentClarifierService = intentClarifierService;
        this.logBroadcaster = logBroadcaster;
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
        List<Map<String, String>> stages = new ArrayList<>();
        stages.add(Map.of("name", "Analysis", "state", "done", "icon", "✓"));
        stages.add(Map.of("name", "Decision", "state", "done", "icon", "✓"));
        stages.add(Map.of("name", "Compile", "state", "active", "icon", "◉"));
        stages.add(Map.of("name", "Test", "state", "queued", "icon", "•"));
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
            String intentAnalysis = intentClarifierService.analyzeFeatureStoryIntent(storyInput);
            logBroadcaster.publish(LogEvent.success("Analysis phase completed: " + intentAnalysis));
            return ResponseEntity.accepted().body(Map.of(
                    "status", "initiated",
                    "message", "Intent analysis started.",
                    "summary", intentAnalysis));
        } catch (Exception e) {
            logBroadcaster.publish(LogEvent.error("Pipeline orchestration failed: " + e.getMessage()));
            return ResponseEntity.status(500).body(Map.of(
                    "status", "failed",
                    "message", "Anthropic request failed. You can retry.",
                    "error", e.getMessage()));
        }
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

    @PostMapping("/connection/test")
    public ResponseEntity<Map<String, Object>> testConnection(@ModelAttribute ConnectionConfig formConfig) {
        configService.save(formConfig);
        LlmConnectionChecker checker = formConfig.getProvider() == ConnectionConfig.Provider.CLAUDE_API
                ? claudeApiConnectionChecker
                : copilotConnectionChecker;
        executor.submit(() -> checker.validate(formConfig));
        return ResponseEntity.accepted().body(Map.of(
                "status", "queued",
                "message", "Connection test started. Please watch the live log."));
    }
}
