package com.relay.orchestrator.service;

import java.util.List;

/**
 * The structured output of a single clarifier call.
 *
 * The clarifier runs a tool-use loop and submits this via
 * submit_clarification_report. The shape is stable across turns;
 * later phases may replace individual fields with tool-driven JSON.
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
        String estimatedComplexity,
        Intent recommendedIntent,
        Confidence intentConfidence) {

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

    /**
     * Which pipeline the session should run.
     *
     * PLAN     — multi-file change or unclear approach; full plan→implement→review→apply
     * EXPLAIN  — "how does this work?"; analysis only, no code change
     * FIX      — small defect fix; skips plan generation and approval
     * DOCUMENT — docs, comments, or README; no behavior change
     * REVIEW   — audit an existing diff or file
     */
    public enum Intent {
        PLAN,
        EXPLAIN,
        FIX,
        DOCUMENT,
        REVIEW
    }

    public static ClarificationResult blocked(String explanation) {
        return new ClarificationResult(
                State.BLOCKED,
                Confidence.LOW,
                explanation,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                "unknown",
                Intent.PLAN,
                Confidence.LOW);
    }

    public static ClarificationResult error(String explanation) {
        return blocked("Clarifier error: " + explanation);
    }

    /**
     * Null-safe accessor. Legacy sessions persisted before the intent
     * fields existed deserialize with recommendedIntent == null; callers
     * should use this rather than reading the field directly.
     */
    public Intent effectiveIntent() {
        return recommendedIntent != null ? recommendedIntent : Intent.PLAN;
    }

    public Confidence effectiveIntentConfidence() {
        return intentConfidence != null ? intentConfidence : Confidence.LOW;
    }
}