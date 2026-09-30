package com.relay.orchestrator.test;

/** One failing test, extracted from the surefire/Gradle XML report. */
public record TestFailure(
        String className,
        String testName,
        String message,
        String stackTrace,
        Kind kind) {

    public enum Kind {
        FAILURE, // assertion failed
        ERROR, // exception thrown
        SKIPPED // reported as skipped — surfaced but not counted as failure
    }

    public String shortLabel() {
        return className + "." + testName;
    }
}