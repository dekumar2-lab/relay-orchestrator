package com.relay.orchestrator.tokens;

import com.relay.orchestrator.agent.AgentRole;

import java.time.LocalDateTime;

/**
 * One row per LLM call.
 *
 * "Before" numbers are local estimates of the untrimmed prompt/response.
 * "After" numbers are exact values from the API response. Today they're
 * nearly identical because no compression is active yet. Once prompt
 * caching / retrieval trimming land, the gap becomes the savings proof.
 */
public record TokenMetrics(
        long id,
        String sessionId,
        AgentRole role,
        String personaName,
        String model,
        int inputTokensBefore,
        int inputTokensAfter,
        int outputTokensBefore,
        int outputTokensAfter,
        int cacheReadTokens,
        int cacheWriteTokens,
        double estimatedCostUsd,
        LocalDateTime createdAt) {

    public double inputReductionPct() {
        if (inputTokensBefore == 0) return 0.0;
        return 100.0 * (inputTokensBefore - inputTokensAfter) / inputTokensBefore;
    }

    public double outputReductionPct() {
        if (outputTokensBefore == 0) return 0.0;
        return 100.0 * (outputTokensBefore - outputTokensAfter) / outputTokensBefore;
    }
}