package com.relay.orchestrator.artifact;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

public record Artifact(
        String id,
        String sessionId,
        ArtifactKind kind,
        String content,
        ArtifactStatus status,
        String verdict, // null except for REVIEW artifacts
        String createdBy,
        String approvedBy,
        LocalDateTime createdAt,
        LocalDateTime approvedAt) {

    @JsonIgnore
    public boolean isDraft() {
        return status == ArtifactStatus.DRAFT;
    }

    @JsonIgnore
    public boolean isApproved() {
        return status == ArtifactStatus.APPROVED;
    }

    @JsonIgnore
    public boolean isRejected() {
        return status == ArtifactStatus.REJECTED;
    }

    @JsonIgnore
    public boolean isExecuted() {
        return status == ArtifactStatus.EXECUTED;
    }

    @JsonIgnore
    public String shortId() {
        return id == null || id.length() < 12 ? id : id.substring(0, 12);
    }
}