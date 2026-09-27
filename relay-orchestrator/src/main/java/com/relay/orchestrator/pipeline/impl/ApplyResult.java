package com.relay.orchestrator.pipeline.impl;

public record ApplyResult(
        boolean ok,
        String message,
        String backupPath,
        int fileCount) {

    public static ApplyResult ok(String backupPath, int fileCount) {
        return new ApplyResult(true, null, backupPath, fileCount);
    }

    public static ApplyResult fail(String message) {
        return new ApplyResult(false, message, null, 0);
    }
}