package com.relay.orchestrator.test;

import com.relay.orchestrator.build.BuildDetection;
import com.relay.orchestrator.build.BuildSystemDetector;
import com.relay.orchestrator.config.AppConfigManager;
import com.relay.orchestrator.config.RepoStatus;
import com.relay.orchestrator.config.RepositoryConfig;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs `mvn clean test` (or Gradle / npm / pytest equivalents) in the
 * live repo root and parses JUnit XML reports.
 *
 * Precondition: the diff has already been applied to disk. The endpoint
 * that calls this handles the apply step; this service does not touch
 * the diff.
 *
 * The 10-minute ceiling exists so a stuck test loop cannot hang the
 * request thread. On timeout the child process is killed and a TIMEOUT
 * result is returned.
 */
@Service
public class TestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerService.class);

    private static final int TIMEOUT_SECONDS = 600;
    private static final long KILL_GRACE_SECONDS = 5;
    private static final int STDOUT_TAIL = 8_000;
    private static final int STDERR_TAIL = 4_000;

    private final AppConfigManager appConfigManager;
    private final BuildSystemDetector buildSystemDetector;
    private final MavenSurefireParser surefireParser;
    private final LogBroadcaster logBroadcaster;

    public TestRunnerService(AppConfigManager appConfigManager,
            BuildSystemDetector buildSystemDetector,
            MavenSurefireParser surefireParser,
            LogBroadcaster logBroadcaster) {
        this.appConfigManager = appConfigManager;
        this.buildSystemDetector = buildSystemDetector;
        this.surefireParser = surefireParser;
        this.logBroadcaster = logBroadcaster;
    }

    public TestRunResult run() {
        RepositoryConfig repo = pickWorkingRepo();
        if (repo == null) {
            return TestRunResult.error("No indexed repository available");
        }

        Path repoRoot = Paths.get(repo.getPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(repoRoot)) {
            return TestRunResult.error("Repo root not found: " + repoRoot);
        }

        BuildDetection detection = buildSystemDetector.detect(repoRoot);
        if (!detection.isDetected()) {
            return TestRunResult.noTests(repoRoot.toString());
        }
        if (!detection.hasTestStep()) {
            return TestRunResult.noTests(repoRoot.toString());
        }

        // Build the full command: clean + test. `clean` forces a fresh
        // compile so stale .class files can't hide a broken diff.
        List<String> args = new ArrayList<>();
        args.add("clean");
        args.addAll(detection.getTestArgs());

        logBroadcaster.publish(LogEvent.info(
                "[TESTS] Running: " + detection.getExecutable() + " " + args));
        logBroadcaster.publish(LogEvent.info(
                "[TESTS] Working dir: " + repoRoot));

        CommandResult cr;
        try {
            cr = runCommand(detection.getExecutable(), args, repoRoot, TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.error("Test command failed to launch", e);
            logBroadcaster.publish(LogEvent.error("[TESTS] " + e.getMessage()));
            return TestRunResult.error(e.getMessage());
        }

        // Parse reports regardless of exit code — partial results matter.
        int totalTests = surefireParser.countTests(repoRoot);
        List<TestFailure> failures = surefireParser.parseFailures(repoRoot);
        int failureCount = (int) failures.stream()
                .filter(f -> f.kind() != TestFailure.Kind.SKIPPED)
                .count();
        int skipCount = (int) failures.stream()
                .filter(f -> f.kind() == TestFailure.Kind.SKIPPED)
                .count();
        int passedCount = Math.max(0, totalTests - failureCount - skipCount);

        TestRunResult.Status status;
        if (cr.timedOut()) {
            status = TestRunResult.Status.TIMEOUT;
        } else if (cr.exitCode() == 0) {
            status = TestRunResult.Status.TESTS_PASSED;
        } else if (looksLikeCompileError(cr.stdout(), cr.stderr())) {
            status = TestRunResult.Status.COMPILE_FAILED;
        } else if (totalTests == 0 && failures.isEmpty()) {
            status = TestRunResult.Status.ERROR;
        } else {
            status = TestRunResult.Status.TESTS_FAILED;
        }

        if (status == TestRunResult.Status.TESTS_PASSED) {
            logBroadcaster.publish(LogEvent.success(
                    "[TESTS] " + passedCount + " passed in " + cr.durationMs() + "ms"));
        } else if (status == TestRunResult.Status.COMPILE_FAILED) {
            logBroadcaster.publish(LogEvent.error(
                    "[TESTS] Compilation failed (exit=" + cr.exitCode() + ")"));
        } else if (status == TestRunResult.Status.TIMEOUT) {
            logBroadcaster.publish(LogEvent.error(
                    "[TESTS] Timed out after " + TIMEOUT_SECONDS + "s"));
        } else {
            logBroadcaster.publish(LogEvent.warn(
                    "[TESTS] " + failureCount + " failure(s), "
                            + passedCount + " passed (exit=" + cr.exitCode() + ")"));
        }

        return new TestRunResult(
                status,
                repoRoot.toString(),
                cr.exitCode(),
                cr.durationMs(),
                passedCount,
                failureCount,
                skipCount,
                failures,
                tail(cr.stdout(), STDOUT_TAIL),
                tail(cr.stderr(), STDERR_TAIL),
                cr.timedOut());
    }

    // ------------------------------------------------------------------

    private CommandResult runCommand(String executable, List<String> args,
            Path workDir, int timeoutSeconds)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name")
                .toLowerCase().contains("win");
        boolean isScript = executable != null
                && (executable.endsWith(".cmd") || executable.endsWith(".bat"));
        if (isWindows && isScript) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(executable);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());

        long start = System.currentTimeMillis();
        Process p = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outT = new Thread(() -> pump(p.getInputStream(), stdout), "test-stdout");
        Thread errT = new Thread(() -> pump(p.getErrorStream(), stderr), "test-stderr");
        outT.setDaemon(true);
        errT.setDaemon(true);
        outT.start();
        errT.start();

        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - start;

        if (!finished) {
            p.destroyForcibly();
            try {
                p.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            outT.join(2000);
            errT.join(2000);
            return new CommandResult(-1, stdout.toString(), stderr.toString(), duration, true);
        }

        outT.join(3000);
        errT.join(3000);
        return new CommandResult(p.exitValue(), stdout.toString(), stderr.toString(), duration, false);
    }

    private void pump(InputStream in, StringBuilder sb) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
    }

    private boolean looksLikeCompileError(String stdout, String stderr) {
        String combined = (stdout == null ? "" : stdout)
                + "\n" + (stderr == null ? "" : stderr);
        return combined.contains("COMPILATION ERROR")
                || combined.contains("Compilation failure")
                || combined.contains("compilation failed")
                || combined.contains("cannot find symbol");
    }

    private RepositoryConfig pickWorkingRepo() {
        for (RepositoryConfig repo : appConfigManager.getRepositories()) {
            if (repo.getStatus() == RepoStatus.INDEXED
                    || repo.getStatus() == RepoStatus.STALE) {
                return repo;
            }
        }
        return null;
    }

    private String tail(String s, int max) {
        if (s == null || s.isEmpty())
            return "";
        return s.length() <= max ? s : "... [truncated]\n" + s.substring(s.length() - max);
    }

    private record CommandResult(int exitCode, String stdout, String stderr,
            long durationMs, boolean timedOut) {
    }

    // ------------------------------------------------------------------
    // Compile-only — used by the compile gate, not by the manual test runner.
    // No "clean" prefix. Incremental compile.
    // ------------------------------------------------------------------

    public CompileResult compileOnly(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return CompileResult.skipped("Repo root not found: " + repoRoot);
        }

        BuildDetection detection = buildSystemDetector.detect(repoRoot);
        if (!detection.isDetected()) {
            return CompileResult.skipped("No build system detected");
        }
        if (!detection.hasCompileStep()) {
            return CompileResult.skipped("Build system has no compile step");
        }

        List<String> args = detection.getCompileArgs();
        logBroadcaster.publish(LogEvent.info(
                "[COMPILE] Running: " + detection.getExecutable() + " " + args));
        logBroadcaster.publish(LogEvent.info(
                "[COMPILE] Working dir: " + repoRoot));

        CommandResult cr;
        try {
            cr = runCommand(detection.getExecutable(), args, repoRoot, TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.error("Compile command failed to launch", e);
            logBroadcaster.publish(LogEvent.error("[COMPILE] " + e.getMessage()));
            return CompileResult.failed(-1, "", e.getMessage());
        }

        boolean passed = !cr.timedOut() && cr.exitCode() == 0;
        if (passed) {
            logBroadcaster.publish(LogEvent.success(
                    "[COMPILE] Passed in " + cr.durationMs() + "ms"));
        } else if (cr.timedOut()) {
            logBroadcaster.publish(LogEvent.error(
                    "[COMPILE] Timed out after " + TIMEOUT_SECONDS + "s"));
        } else {
            logBroadcaster.publish(LogEvent.warn(
                    "[COMPILE] Failed (exit=" + cr.exitCode() + ")"));
        }

        return new CompileResult(
                false,
                passed,
                cr.exitCode(),
                cr.durationMs(),
                tail(cr.stdout(), STDOUT_TAIL),
                tail(cr.stderr(), STDERR_TAIL),
                cr.timedOut());
    }

    public record CompileResult(
            boolean skipped,
            boolean passed,
            int exitCode,
            long durationMs,
            String stdoutTail,
            String stderrTail,
            boolean timedOut) {

        public static CompileResult skipped(String reason) {
            return new CompileResult(true, true, 0, 0, "", reason, false);
        }

        public static CompileResult failed(int exitCode, String stdout, String stderr) {
            return new CompileResult(false, false, exitCode, 0, stdout, stderr, false);
        }

        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (stdoutTail != null && !stdoutTail.isBlank()) {
                sb.append(stdoutTail);
            }
            if (stderrTail != null && !stderrTail.isBlank()) {
                if (sb.length() > 0)
                    sb.append("\n");
                sb.append(stderrTail);
            }
            return sb.toString();
        }
    }
}