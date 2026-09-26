package com.relay.orchestrator.pipeline.impl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.List;

public record ImplementationResult(
        String summary,
        List<FileDiff> files,
        int totalAdditions,
        int totalDeletions,
        int turnCount,
        String stopReason, // "SUBMITTED", "TURN_CAP", "ERROR"
        LocalDateTime completedAt) {

    public record FileDiff(
            String path,
            String changeKind, // CREATE / MODIFY / DELETE
            String unifiedDiff,
            int additions,
            int deletions) {
    }

    @JsonIgnore
    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }
}