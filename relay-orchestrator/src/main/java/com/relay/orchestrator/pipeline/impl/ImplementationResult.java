package com.relay.orchestrator.pipeline.impl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ImplementationResult(
        String summary,
        List<FileDiff> files,
        int totalAdditions,
        int totalDeletions,
        int turnCount,
        String stopReason,
        LocalDateTime completedAt) {

    @JsonIgnore
    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileDiff(
            String path,
            String changeKind, // CREATE / MODIFY / DELETE
            String unifiedDiff, // for display
            String beforeContent, // null for CREATE; full disk content at impl time
            String afterContent, // null for DELETE; full content to write
            int additions,
            int deletions) {
    }
}