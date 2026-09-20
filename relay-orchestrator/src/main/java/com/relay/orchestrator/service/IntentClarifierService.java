package com.relay.orchestrator.service;

import com.relay.orchestrator.config.AppConfigManager;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.logging.TokenTrackerService;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

@Service
public class IntentClarifierService {

    private final AppConfigManager configManager;
    private final LogBroadcaster logBroadcaster;
    private final TokenTrackerService tokenTracker;
    private final RestClient restClient = RestClient.create();

    public IntentClarifierService(AppConfigManager configManager,
            LogBroadcaster logBroadcaster,
            TokenTrackerService tokenTracker) {
        this.configManager = configManager;
        this.logBroadcaster = logBroadcaster;
        this.tokenTracker = tokenTracker;
    }

    public String analyzeFeatureStoryIntent(String storyInput) {
        logBroadcaster.publish(LogEvent.info("[AGENT: CLARIFIER] Booting intent extraction pipeline track..."));

        // System Prompt Schema forcing Anthropic to respond with predictable
        // classification parameters
        String systemInstruction = "You are a senior system architect. Analyze the provided user feature story. " +
                "Identify the structural software development context intent. Output a concise summary of the classes, "
                +
                "packages, and files that are relevant to implementing this change.";

        try {
            String apiKey = configManager.getAnthropicApiKey();
            String model = configManager.getModel();

            if (apiKey == null || apiKey.isBlank()) {
                logBroadcaster.publish(LogEvent.warn("Anthropic API key missing for intent clarifier"));
                return "ERROR: Anthropic API key missing";
            }

            Map<?, ?> response = restClient.post()
                    .uri("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "max_tokens", 1000,
                            "system", systemInstruction,
                            "messages", List.of(Map.of("role", "user", "content", storyInput))))
                    .retrieve()
                    .body(Map.class);

            // Extract usage metrics token counts dynamically for our tracker ledger rows
            if (response != null && response.containsKey("usage")) {
                Map<?, ?> usage = (Map<?, ?>) response.get("usage");
                int inputTokens = usage.get("input_tokens") != null ? ((Number) usage.get("input_tokens")).intValue()
                        : 0;
                int outputTokens = usage.get("output_tokens") != null ? ((Number) usage.get("output_tokens")).intValue()
                        : 0;

                tokenTracker.logAnthropicUsage("CLARIFIER_RUN", "claude-3-5-sonnet", inputTokens, outputTokens);
            }

            // Extract text completion content
            List<?> contentList = (List<?>) response.get("content");
            if (contentList != null && !contentList.isEmpty()) {
                Map<?, ?> textMap = (Map<?, ?>) contentList.get(0);
                return String.valueOf(textMap.get("text"));
            }

            return "Failed to parse structured intent content bounds.";
        } catch (Exception e) {
            String errorMsg = "Intent Clarifier execution error instance: " + e.getMessage();
            logBroadcaster.publish(LogEvent.error(errorMsg));
            return "ERROR";
        }
    }
}
