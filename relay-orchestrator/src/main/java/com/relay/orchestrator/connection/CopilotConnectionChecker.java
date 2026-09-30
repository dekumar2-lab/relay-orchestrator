package com.relay.orchestrator.connection;

import com.relay.orchestrator.llm.LlmClient;
import com.relay.orchestrator.llm.LlmClientRouter;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import org.springframework.stereotype.Service;

@Service
public class CopilotConnectionChecker implements LlmConnectionChecker {

    private final LlmClientRouter router;
    private final LogBroadcaster broadcaster;
    private final ConnectionStatusService statusService;

    public CopilotConnectionChecker(LlmClientRouter router,
            LogBroadcaster broadcaster,
            ConnectionStatusService statusService) {
        this.router = router;
        this.broadcaster = broadcaster;
        this.statusService = statusService;
    }

    @Override
public void validate(ConnectionConfig config) {
    String providerName = config.getProvider().name();
    String label = config.getProvider().getLabel();

    broadcaster.publish(LogEvent.info("Checking " + label + " access..."));

    if (config.getGithubToken() == null || config.getGithubToken().isBlank()) {
        broadcaster.publish(LogEvent.error(
                "No GitHub token — paste a fine-grained PAT (github_pat_) or "
                        + "OAuth token (gho_) in Settings."));
        statusService.markFailure(providerName);
        return;
    }

    LlmClient.ProbeResult result = router.probe(config);
    if (result.ok()) {
        broadcaster.publish(LogEvent.success(result.detail()));
        broadcaster.publish(LogEvent.success(label + " is reachable"));
        statusService.markSuccess(providerName);
    } else {
        broadcaster.publish(LogEvent.warn(result.detail()));
        statusService.markFailure(providerName);
    }
}
}