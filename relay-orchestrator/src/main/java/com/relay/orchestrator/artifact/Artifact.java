package com.relay.orchestrator.artifact;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * A durable, reviewable document produced by a Producer.
 *
 * Plans, designs, RCAs, and reviews are all Artifacts. The approval
 * gate sits between DRAFT and APPROVED — no executor runs against an
 * artifact that hasn't been approved by a human.
 *
 * Content is markdown. Producers assemble it from structured tool-call
 * output so every artifact of a given kind has the same shape.
 */
public record Artifact(
        String id,
        String sessionId,
        ArtifactKind kind,
        String content,
        ArtifactStatus status,
        String createdBy, // persona name, e.g. "oscar"
        String approvedBy, // null until approved
        LocalDateTime createdAt,
        LocalDateTime approvedAt) { // null until approved

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

    /** Short display id for UI use. */
    @JsonIgnore
    public String shortId() {
        return id == null || id.length() < 12 ? id : id.substring(0, 12);
    }
}