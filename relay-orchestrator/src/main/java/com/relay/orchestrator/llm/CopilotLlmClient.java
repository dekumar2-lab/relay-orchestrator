package com.relay.orchestrator.llm;

import com.relay.orchestrator.connection.ConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GitHub Copilot implementation. Currently a stub - the Copilot Java SDK is
 * on a public-preview release whose API is in flux, and we do NOT guess at it.
 *
 * When a Copilot subscription is available, the plan is:
 *   1. Read config.getGithubToken() (same pattern as AnthropicLlmClient reads
 *      the Anthropic key).
 *   2. Construct a CopilotClient with CopilotClientOptions.setGitHubToken(token).
 *   3. Start the client, create a session, send the prompt(s), collect the reply.
 *   4. Map the reply into the same LlmResponse record the Anthropic client returns.
 *
 * The LlmClient interface does not care which provider is behind it. That is
 * the entire point of this abstraction.
 */
@Service
public class CopilotLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(CopilotLlmClient.class);

    private static final String NOT_READY =
            "GitHub Copilot provider is not yet wired. The Copilot Java SDK is on a "
                    + "public-preview release whose API is in flux, and this build does not "
                    + "guess at it. Use 'Claude API (direct)' for now.";

    @Override
    public ProviderId providerId() {
        return ProviderId.COPILOT;
    }

    @Override
    public LlmResponse complete(LlmRequest request, ConnectionConfig config) {
        // Real implementation will go here. For now, fail loudly so callers
        // don't silently get an empty response.
        throw new UnsupportedOperationException(NOT_READY);
    }

    @Override
    public ProbeResult probe(ConnectionConfig config) {
        log.debug("Copilot probe requested but not implemented");
        return ProbeResult.fail(NOT_READY);
    }
}