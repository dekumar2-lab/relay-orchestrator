package com.relay.orchestrator.build;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of scanning a repository for a build system.
 *
 * The compile step is optional — Python repos have no compile phase.
 * The test step is optional too — some repos ship without tests.
 *
 * Both commands run with the repo root as working directory.
 */
public class BuildDetection {

    private final BuildSystem system;
    private final Path repoRoot;
    private final String executable;
    private final List<String> compileArgs;
    private final List<String> testArgs;

    public BuildDetection(BuildSystem system,
                          Path repoRoot,
                          String executable,
                          List<String> compileArgs,
                          List<String> testArgs) {
        this.system = system;
        this.repoRoot = repoRoot;
        this.executable = executable;
        this.compileArgs = compileArgs;
        this.testArgs = testArgs;
    }

    public static BuildDetection unknown(Path repoRoot) {
        return new BuildDetection(BuildSystem.UNKNOWN, repoRoot, null, null, null);
    }

    public BuildSystem getSystem()      { return system; }
    public Path getRepoRoot()           { return repoRoot; }
    public String getExecutable()       { return executable; }
    public List<String> getCompileArgs(){ return compileArgs; }
    public List<String> getTestArgs()   { return testArgs; }

    public boolean isDetected() {
        return system != BuildSystem.UNKNOWN;
    }

    public boolean hasCompileStep() {
        return compileArgs != null && !compileArgs.isEmpty();
    }

    public boolean hasTestStep() {
        return testArgs != null && !testArgs.isEmpty();
    }

    @Override
    public String toString() {
        return "BuildDetection[system=" + system
                + ", executable=" + executable
                + ", compileArgs=" + compileArgs
                + ", testArgs=" + testArgs + "]";
    }
}