package com.relay.model;

public class FileCandidate {

    private String filePath;
    private double confidence;
    private String reason;
    private int downstreamCount;
    private String language;

    public FileCandidate() {
    }

    public FileCandidate(String filePath, double confidence, String reason, int downstreamCount, String language) {
        this.filePath = filePath;
        this.confidence = confidence;
        this.reason = reason;
        this.downstreamCount = downstreamCount;
        this.language = language;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getDownstreamCount() {
        return downstreamCount;
    }

    public void setDownstreamCount(int downstreamCount) {
        this.downstreamCount = downstreamCount;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}