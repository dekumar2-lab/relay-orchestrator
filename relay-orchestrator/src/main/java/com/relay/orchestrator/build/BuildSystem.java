package com.relay.orchestrator.build;

/**
 * Enumerated set of build systems Relay knows how to invoke.
 * Detection is file-based (see BuildSystemDetector).
 * The actual command construction lives in BuildDetection.
 */
public enum BuildSystem {

    MAVEN("maven"),
    GRADLE("gradle"),
    NPM("npm"),
    YARN("yarn"),
    PNPM("pnpm"),
    PYTEST("pytest"),
    UNKNOWN("unknown");

    private final String id;

    BuildSystem(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}