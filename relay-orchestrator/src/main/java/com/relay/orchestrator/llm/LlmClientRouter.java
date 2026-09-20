package com.relay.orchestrator.llm;

import com.relay.orchestrator.connection.ConnectionConfig;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes LLM calls to the correct provider based on ConnectionConfig.
 * Every agent in Phase 2+ obtains its LlmClient through here.
 *
 * Provider-agnostic: no imports of AnthropicLlmClient or CopilotLlmClient,
 * no instanceof checks, no provider-specific branches. Adding a new provider
 * means writing one new LlmClient implementation and registering it as a
 * @Service — nothing here needs to change.
 */
@Service
public class LlmClientRouter {

    private final Map<LlmClient.ProviderId, LlmClient> clients =
            new EnumMap<>(LlmClient.ProviderId.class);

    public LlmClientRouter(List<LlmClient> allClients) {
        for (LlmClient c : allClients) {
            clients.put(c.providerId(), c);
        }
    }

    /** Look up the client for the given config without making a call. */
    public LlmClient forConfig(ConnectionConfig config) {
        LlmClient.ProviderId id = providerIdOf(config);
        LlmClient client = clients.get(id);
        if (client == null) {
            throw new IllegalStateException(
                    "No LlmClient registered for provider: " + id);
        }
        return client;
    }

    /**
     * Convenience: fetch the right client and run a completion in one call.
     * The client pulls the credential it needs out of the config itself.
     */
    public LlmClient.LlmResponse complete(ConnectionConfig config,
                                          LlmClient.LlmRequest request) {
        return forConfig(config).complete(request, config);
    }

    /**
     * Convenience: streaming completion, delegates to the active client.
     */
    public LlmClient.LlmResponse completeStreaming(ConnectionConfig config,
                                                   LlmClient.LlmRequest request,
                                                   LlmClient.TokenCallback onToken) {
        return forConfig(config).completeStreaming(request, config, onToken);
    }

    /** Convenience: health probe against the configured provider. */
    public LlmClient.ProbeResult probe(ConnectionConfig config) {
        return forConfig(config).probe(config);
    }

    private LlmClient.ProviderId providerIdOf(ConnectionConfig config) {
        return switch (config.getProvider()) {
            case CLAUDE_API -> LlmClient.ProviderId.ANTHROPIC;
            case GITHUB_COPILOT -> LlmClient.ProviderId.COPILOT;
        };
    }
}