package com.relay.orchestrator.config;

import java.time.LocalDateTime;

public class RepositoryConfig {
    private String id;
    private String path;
    private SourceType sourceType = SourceType.LOCAL_PATH;
    private LocalDateTime lastIndexed;
    private RepoStatus status = RepoStatus.NOT_INDEXED;

    // Getters, Setters, and Constructors
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public LocalDateTime getLastIndexed() {
        return lastIndexed;
    }

    public void setLastIndexed(LocalDateTime lastIndexed) {
        this.lastIndexed = lastIndexed;
    }

    public RepoStatus getStatus() {
        return status;
    }

    public void setStatus(RepoStatus status) {
        this.status = status;
    }
}
