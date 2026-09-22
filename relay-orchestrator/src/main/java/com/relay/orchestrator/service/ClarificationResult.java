package com.relay.orchestrator.service;

import java.util.List;

/**
 * The structured output of a single clarifier call.
 *
 * Step 1.2a.1 populates this from a free-text LLM response that has been
 * parsed with a section-aware regex. Later steps will replace the parsing
 * with tool-use JSON. The shape stays the same.
 */
public record ClarificationResult(
        State state,
        Confidence confidence,
        String summary,
        List<String> relevantFiles,
        List<String> symbolsLikelyAffected,
        List<ClarifyingQuestion> questions,
        List<String> riskNotes,
        List<String> assumptionsMade,
        String estimatedComplexity) {

    public enum State {
        READY,
        NEEDS_INPUT,
        BLOCKED
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public static ClarificationResult blocked(String explanation) {
        return new ClarificationResult(
                State.BLOCKED,
                Confidence.LOW,
                explanation,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                "unknown");
    }

    public static ClarificationResult error(String explanation) {
        return blocked("Clarifier error: " + explanation);
    }
}