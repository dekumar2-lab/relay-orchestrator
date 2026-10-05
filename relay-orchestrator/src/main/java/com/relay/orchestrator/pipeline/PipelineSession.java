package com.relay.orchestrator.pipeline;

import com.relay.orchestrator.pipeline.impl.ImplementationResult;
import com.relay.orchestrator.service.ClarificationResult;

import java.time.LocalDateTime;
import java.util.List;

public record PipelineSession(
        String id,
        String originalStory,
        PipelineStage stage,
        int turnCount,
        int maxTurns,
        List<AnsweredQuestion> qaHistory,
        ClarificationResult lastResult,
        ImplementationResult implementationResult,
        String compileErrors,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PipelineSession fresh(String id, String story, int maxTurns) {
        LocalDateTime now = LocalDateTime.now();
        return new PipelineSession(
                id, story, PipelineStage.NEW,
                0, maxTurns,
                List.of(), null, null, null,
                now, now);
    }

    public PipelineSession with(PipelineStage newStage, ClarificationResult newResult) {
        return new PipelineSession(
                id, originalStory, newStage,
                turnCount, maxTurns,
                qaHistory, newResult != null ? newResult : lastResult,
                implementationResult,
                compileErrors,
                createdAt, LocalDateTime.now());
    }

    public PipelineSession incrementTurn() {
        return new PipelineSession(
                id, originalStory, stage,
                turnCount + 1, maxTurns,
                qaHistory, lastResult, implementationResult,
                compileErrors,
                createdAt, LocalDateTime.now());
    }

    public PipelineSession withTurn(List<AnsweredQuestion> newHistory,
            ClarificationResult newResult) {
        return new PipelineSession(
                id, originalStory, stage,
                turnCount + 1, maxTurns,
                newHistory, newResult, implementationResult,
                compileErrors,
                createdAt, LocalDateTime.now());
    }

    public PipelineSession withImplementation(ImplementationResult result) {
        PipelineStage next = result == null || result.isEmpty()
                ? PipelineStage.IMPLEMENTATION_FAILED
                : PipelineStage.IMPLEMENTATION_READY;
        return new PipelineSession(
                id, originalStory, next,
                turnCount, maxTurns,
                qaHistory, lastResult, result,
                null,
                createdAt, LocalDateTime.now());
    }

    /** Set compile errors and move to COMPILE_FAILED. Clears any prior stage. */
    public PipelineSession withCompileErrors(String errors) {
        return new PipelineSession(
                id, originalStory, PipelineStage.COMPILE_FAILED,
                turnCount, maxTurns,
                qaHistory, lastResult, implementationResult,
                errors,
                createdAt, LocalDateTime.now());
    }

    public boolean canAskAgain() {
        return turnCount < maxTurns;
    }
}