package com.relay.orchestrator.connection;

import com.relay.orchestrator.llm.LlmClient;
import com.relay.orchestrator.llm.LlmClientRouter;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import org.springframework.stereotype.Service;

@Service
public class ClaudeApiConnectionChecker implements LlmConnectionChecker {

    private final LlmClientRouter router;
    private final LogBroadcaster broadcaster;
    private final ConnectionStatusService statusService;

    public ClaudeApiConnectionChecker(LlmClientRouter router,
            LogBroadcaster broadcaster,
            ConnectionStatusService statusService) {
        this.router = router;
        this.broadcaster = broadcaster;
        this.statusService = statusService;
    }

    @Override
    public void validate(ConnectionConfig config) {
        String providerName = config.getProvider().name();

        broadcaster.publish(LogEvent.info("Validating Anthropic API key..."));

        if (config.getAnthropicApiKey() == null || config.getAnthropicApiKey().isBlank()) {
            broadcaster.publish(LogEvent.error(
                    "No Anthropic API key entered - create one at console.anthropic.com/settings/keys"));
            statusService.markFailure(providerName);
            return;
        }

        if (config.getModel() == null || config.getModel().isBlank()) {
            broadcaster.publish(LogEvent.error("No model configured - set one on the Settings page"));
            statusService.markFailure(providerName);
            return;
        }

        broadcaster.publish(LogEvent.info("Sending test prompt to model: " + config.getModel()));

        LlmClient.ProbeResult result = router.probe(config);
        if (result.ok()) {
            broadcaster.publish(LogEvent.success(result.detail()));
            broadcaster.publish(LogEvent.success(
                    "Connection validated - model " + config.getModel() + " is reachable with this key"));
            statusService.markSuccess(providerName);
        } else {
            broadcaster.publish(LogEvent.error("Claude API check failed: " + result.detail()));
            statusService.markFailure(providerName);
        }
    }
}