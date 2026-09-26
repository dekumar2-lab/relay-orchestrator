package com.relay.orchestrator.pipeline.impl;

/**
 * One proposed file change held in memory. Nothing is written to disk
 * until the user explicitly applies the diff.
 */
public record StagedChange(
        String path, // relative to repo root
        Kind kind,
        String before, // null for CREATE
        String after) { // null for DELETE

    public enum Kind {
        CREATE, MODIFY, DELETE
    }
}