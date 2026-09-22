package com.relay.orchestrator.pipeline;

import com.relay.orchestrator.service.ClarificationResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The complete state of one pipeline run.
 *
 * This is deliberately a flat record — everything needed to resume
 * is in here or in the session store. LangGraph4j migration will lift
 * this shape directly into a graph state.
 */
public record PipelineSession(
        String id,
        String originalStory,
        PipelineStage stage,
        int turnCount,
        int maxTurns,
        List<AnsweredQuestion> qaHistory,
        ClarificationResult lastResult,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PipelineSession fresh(String id, String story, int maxTurns) {
        LocalDateTime now = LocalDateTime.now();
        return new PipelineSession(
                id, story, PipelineStage.NEW,
                0, maxTurns,
                List.of(), null,
                now, now);
    }

    /** Returns a copy with the given stage and optional result update. */
    public PipelineSession with(PipelineStage newStage, ClarificationResult newResult) {
        return new PipelineSession(
                id, originalStory, newStage,
                turnCount, maxTurns,
                qaHistory, newResult != null ? newResult : lastResult,
                createdAt, LocalDateTime.now());
    }

    /** Returns a copy with turnCount incremented. Nothing else changes. */
    public PipelineSession incrementTurn() {
        return new PipelineSession(
                id, originalStory, stage,
                turnCount + 1, maxTurns,
                qaHistory, lastResult,
                createdAt, LocalDateTime.now());
    }

    public PipelineSession withTurn(List<AnsweredQuestion> newHistory, ClarificationResult newResult) {
        return new PipelineSession(
                id, originalStory, stage,
                turnCount + 1, maxTurns,
                newHistory, newResult,
                createdAt, LocalDateTime.now());
    }

    public boolean canAskAgain() {
        return turnCount < maxTurns;
    }
}