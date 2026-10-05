package com.relay.orchestrator.pipeline.impl;

import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.pipeline.PipelineSession;
import com.relay.orchestrator.test.TestRunnerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Compile gate for the implementer pipeline.
 *
 * Flow:
 * apply diff → compile → undo
 * ├─ pass → PASSED
 * └─ fail → undo, retry pass (implementFix), apply, compile, undo
 * ├─ pass → RECOVERED (with new result)
 * └─ fail → FAILED (with compiler output)
 *
 * Every path that applies must undo. Failures inside compileOnly must
 * still undo. The try/finally below guarantees this.
 *
 * No test-run inside this gate. Compile only. Tests remain user-triggered.
 */
@Service
public class ImplementerVerificationService {

    private static final Logger log = LoggerFactory.getLogger(ImplementerVerificationService.class);

    private final ApplyService applyService;
    private final TestRunnerService testRunnerService;
    private final ImplementerService implementerService;
    private final LogBroadcaster logBroadcaster;

    public ImplementerVerificationService(ApplyService applyService,
            TestRunnerService testRunnerService,
            ImplementerService implementerService,
            LogBroadcaster logBroadcaster) {
        this.applyService = applyService;
        this.testRunnerService = testRunnerService;
        this.implementerService = implementerService;
        this.logBroadcaster = logBroadcaster;
    }

    public VerificationOutcome verify(PipelineSession session,
            ImplementationResult result,
            Path repoRoot) {

        if (result == null || result.isEmpty()) {
            return VerificationOutcome.skipped(result, "no files to verify");
        }

        // ---- Attempt 1 ----
        CompileAttempt first = applyAndCompile(session, result, repoRoot);
        if (first.outcome != null) {
            // apply failed, or compile step skipped
            return first.outcome;
        }
        if (first.passed) {
            logBroadcaster.publish(LogEvent.success(
                    "[COMPILE-GATE] Passed (attempt 1)"));
            return VerificationOutcome.passed(result);
        }

        // ---- Compile failed. Retry. ----
        logBroadcaster.publish(LogEvent.warn(
                "[COMPILE-GATE] Failed on attempt 1 — retrying with compiler errors"));

        ImplementationResult retryResult;
        try {
            retryResult = implementerService.implementFix(
                    session, first.compileOutput, result.files());
        } catch (Exception e) {
            log.error("Retry pass threw", e);
            logBroadcaster.publish(LogEvent.error(
                    "[COMPILE-GATE] Retry pass crashed: " + e.getMessage()));
            return VerificationOutcome.failed(result, first.compileOutput,
                    "Retry pass failed: " + e.getMessage());
        }

        if (retryResult == null || retryResult.isEmpty()) {
            logBroadcaster.publish(LogEvent.error(
                    "[COMPILE-GATE] Retry produced no files"));
            return VerificationOutcome.failed(result, first.compileOutput,
                    "Retry produced no files");
        }

        // ---- Attempt 2 ----
        CompileAttempt second = applyAndCompile(session, retryResult, repoRoot);
        if (second.outcome != null) {
            return second.outcome;
        }
        if (second.passed) {
            logBroadcaster.publish(LogEvent.success(
                    "[COMPILE-GATE] Passed on retry"));
            return VerificationOutcome.recovered(retryResult);
        }

        logBroadcaster.publish(LogEvent.error(
                "[COMPILE-GATE] Failed after retry — surfacing COMPILE_FAILED"));
        return VerificationOutcome.failed(retryResult, second.compileOutput,
                "Compile failed after retry");
    }

    // ------------------------------------------------------------------

    /**
     * Apply the result, compile, undo. Returns either a completed outcome
     * (apply-fail or skipped), or an attempt record with compile result.
     * Always undoes on the path where apply succeeded.
     */
    private CompileAttempt applyAndCompile(PipelineSession session,
            ImplementationResult result,
            Path repoRoot) {

        ApplyResult ar = applyService.apply(session);
        if (!ar.ok()) {
            logBroadcaster.publish(LogEvent.error(
                    "[COMPILE-GATE] Apply failed: " + ar.message()));
            return CompileAttempt.shortCircuit(
                    VerificationOutcome.error(result, "apply failed: " + ar.message()));
        }

        try {
            TestRunnerService.CompileResult cr = testRunnerService.compileOnly(repoRoot);

            if (cr.skipped()) {
                logBroadcaster.publish(LogEvent.info(
                        "[COMPILE-GATE] Skipped — " + cr.stderrTail()));
                return CompileAttempt.shortCircuit(
                        VerificationOutcome.skipped(result,
                                "compile skipped: " + cr.stderrTail()));
            }

            CompileAttempt attempt = new CompileAttempt();
            attempt.passed = cr.passed();
            attempt.compileOutput = cr.combinedOutput();
            return attempt;
        } finally {
            ApplyResult undoResult = applyService.undo(session);
            if (!undoResult.ok()) {
                log.warn("Compile-gate undo failed: {}", undoResult.message());
                logBroadcaster.publish(LogEvent.warn(
                        "[COMPILE-GATE] Undo failed: " + undoResult.message()));
            }
        }
    }

    private static class CompileAttempt {
        boolean passed;
        String compileOutput;
        VerificationOutcome outcome; // non-null for short-circuit paths

        static CompileAttempt shortCircuit(VerificationOutcome outcome) {
            CompileAttempt a = new CompileAttempt();
            a.outcome = outcome;
            return a;
        }
    }

    // ------------------------------------------------------------------

    public record VerificationOutcome(
            Status status,
            ImplementationResult result,
            String compileErrors,
            String message) {

        public enum Status {
            PASSED, // compiled on first attempt
            RECOVERED, // compiled on retry
            FAILED, // both attempts failed
            SKIPPED, // no compile step, or no files to verify
            ERROR // apply or infrastructure failure
        }

        public static VerificationOutcome passed(ImplementationResult r) {
            return new VerificationOutcome(Status.PASSED, r, null, "compiled");
        }

        public static VerificationOutcome recovered(ImplementationResult r) {
            return new VerificationOutcome(Status.RECOVERED, r, null, "compiled on retry");
        }

        public static VerificationOutcome failed(ImplementationResult r,
                String errors, String message) {
            return new VerificationOutcome(Status.FAILED, r, errors, message);
        }

        public static VerificationOutcome skipped(ImplementationResult r, String message) {
            return new VerificationOutcome(Status.SKIPPED, r, null, message);
        }

        public static VerificationOutcome error(ImplementationResult r, String message) {
            return new VerificationOutcome(Status.ERROR, r, null, message);
        }
    }
}