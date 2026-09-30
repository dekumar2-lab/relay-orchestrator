package com.relay.orchestrator.test;

import java.util.List;

/**
 * Outcome of one test run. Serialized to JSON and persisted as a
 * TEST_RUN artifact. The UI parses it directly; no markdown layer.
 */
public record TestRunResult(
        Status status,
        String repoPath,
        int exitCode,
        long durationMs,
        int passedTests,
        int failedTests,
        int skippedTests,
        List<TestFailure> failures,
        String stdoutTail,
        String stderrTail,
        boolean timedOut) {

    public enum Status {
        TESTS_PASSED,
        TESTS_FAILED,
        COMPILE_FAILED,
        NO_TESTS,
        TIMEOUT,
        ERROR
    }

    public static TestRunResult error(String message) {
        return new TestRunResult(
                Status.ERROR, null, -1, 0, 0, 0, 0,
                List.of(), "", message, false);
    }

    public static TestRunResult noTests(String repoPath) {
        return new TestRunResult(
                Status.NO_TESTS, repoPath, 0, 0, 0, 0, 0,
                List.of(), "", "", false);
    }

    public boolean ok() {
        return status == Status.TESTS_PASSED || status == Status.NO_TESTS;
    }
}