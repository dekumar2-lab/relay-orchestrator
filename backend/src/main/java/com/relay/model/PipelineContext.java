package com.relay.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PipelineContext {

    private String pipelineId = UUID.randomUUID().toString();
    private StoryRequest request;
    private PipelineStage currentStage = PipelineStage.CLARIFY;
    private ComplexityLevel complexity;
    private ExecutionMode effectiveMode = ExecutionMode.FULL_PIPELINE;
    private List<FileCandidate> affectedFiles = new ArrayList<>();
    private Map<String, String> artifacts = new HashMap<>();
    private List<String> logHistory = new ArrayList<>();
    private int retryCount = 0;
    private int clarificationRound = 0;
    private String checkpointToken;
    private boolean awaitingApproval = false;
    private transient CompletableFuture<Boolean> approvalFuture;
    private long inputTokens = 0;
    private long outputTokens = 0;

    public PipelineContext() {
    }

    public void addLog(String message) {
        logHistory.add(message);
        if (logHistory.size() > 50) {
            logHistory.remove(0);
        }
    }

    public void setStage(PipelineStage stage) {
        this.currentStage = stage;
        addLog("Stage: " + stage.name());
    }

    // Getters and setters
    public String getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    public StoryRequest getRequest() {
        return request;
    }

    public void setRequest(StoryRequest request) {
        this.request = request;
    }

    public PipelineStage getCurrentStage() {
        return currentStage;
    }

    public ComplexityLevel getComplexity() {
        return complexity;
    }

    public void setComplexity(ComplexityLevel complexity) {
        this.complexity = complexity;
    }

    public ExecutionMode getEffectiveMode() {
        return effectiveMode;
    }

    public void setEffectiveMode(ExecutionMode effectiveMode) {
        this.effectiveMode = effectiveMode;
    }

    public List<FileCandidate> getAffectedFiles() {
        return affectedFiles;
    }

    public void setAffectedFiles(List<FileCandidate> affectedFiles) {
        this.affectedFiles = affectedFiles;
    }

    public Map<String, String> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(Map<String, String> artifacts) {
        this.artifacts = artifacts;
    }

    public List<String> getLogHistory() {
        return logHistory;
    }

    public void setLogHistory(List<String> logHistory) {
        this.logHistory = logHistory;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getClarificationRound() {
        return clarificationRound;
    }

    public void setClarificationRound(int clarificationRound) {
        this.clarificationRound = clarificationRound;
    }

    public String getCheckpointToken() {
        return checkpointToken;
    }

    public void setCheckpointToken(String checkpointToken) {
        this.checkpointToken = checkpointToken;
    }

    public boolean isAwaitingApproval() {
        return awaitingApproval;
    }

    public void setAwaitingApproval(boolean awaitingApproval) {
        this.awaitingApproval = awaitingApproval;
    }

    public CompletableFuture<Boolean> getApprovalFuture() {
        return approvalFuture;
    }

    public void setApprovalFuture(CompletableFuture<Boolean> approvalFuture) {
        this.approvalFuture = approvalFuture;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }
}