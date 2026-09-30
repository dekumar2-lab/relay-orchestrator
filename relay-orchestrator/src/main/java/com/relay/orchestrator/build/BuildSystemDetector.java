package com.relay.orchestrator.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

@Service
public class BuildSystemDetector {

    private static final Logger log = LoggerFactory.getLogger(BuildSystemDetector.class);

    /**
     * Detect the build system for a repository.
     * Prefers wrapper executables (mvnw.cmd, gradlew.bat) when present.
     * Order matters: the first match wins.
     */
    public BuildDetection detect(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return BuildDetection.unknown(repoRoot);
        }

        // --- Maven ---
        if (Files.exists(repoRoot.resolve("pom.xml"))) {
            String exec = resolveWrapper(repoRoot, "mvnw");
            if (exec == null) exec = "mvn";
            return new BuildDetection(
                    BuildSystem.MAVEN,
                    repoRoot,
                    exec,
                    Arrays.asList("compile", "-q", "-DskipTests"),
                    Arrays.asList("test", "-q"));
        }

        // --- Gradle ---
        if (Files.exists(repoRoot.resolve("build.gradle"))
                || Files.exists(repoRoot.resolve("build.gradle.kts"))) {
            String exec = resolveWrapper(repoRoot, "gradlew");
            if (exec == null) exec = "gradle";
            return new BuildDetection(
                    BuildSystem.GRADLE,
                    repoRoot,
                    exec,
                    Arrays.asList("compileJava", "-q"),
                    Arrays.asList("test", "-q"));
        }

        // --- Node family ---
        if (Files.exists(repoRoot.resolve("package.json"))) {
            if (Files.exists(repoRoot.resolve("pnpm-lock.yaml"))) {
                return new BuildDetection(
                        BuildSystem.PNPM,
                        repoRoot,
                        "pnpm",
                        Arrays.asList("run", "build"),
                        Arrays.asList("test"));
            }
            if (Files.exists(repoRoot.resolve("yarn.lock"))) {
                return new BuildDetection(
                        BuildSystem.YARN,
                        repoRoot,
                        "yarn",
                        Arrays.asList("build"),
                        Arrays.asList("test"));
            }
            return new BuildDetection(
                    BuildSystem.NPM,
                    repoRoot,
                    "npm",
                    Arrays.asList("run", "build"),
                    Arrays.asList("test"));
        }

        // --- Python ---
        if (Files.exists(repoRoot.resolve("pyproject.toml"))
                || Files.exists(repoRoot.resolve("setup.py"))
                || Files.exists(repoRoot.resolve("pytest.ini"))) {
            return new BuildDetection(
                    BuildSystem.PYTEST,
                    repoRoot,
                    "pytest",
                    null,
                    Arrays.asList("-q"));
        }

        log.debug("No build system detected at {}", repoRoot);
        return BuildDetection.unknown(repoRoot);
    }

    /**
     * Look for a wrapper executable (mvnw, mvnw.cmd, gradlew, gradlew.bat).
     * Prefers the Windows variant on Windows, the POSIX variant elsewhere.
     * Returns absolute path, or null if no wrapper is present.
     */
    private String resolveWrapper(Path repoRoot, String baseName) {
        boolean isWindows = System.getProperty("os.name")
                .toLowerCase()
                .contains("win");

        if (isWindows) {
            Path cmd = repoRoot.resolve(baseName + ".cmd");
            if (Files.isRegularFile(cmd)) return cmd.toAbsolutePath().toString();

            Path bat = repoRoot.resolve(baseName + ".bat");
            if (Files.isRegularFile(bat)) return bat.toAbsolutePath().toString();
        }

        Path sh = repoRoot.resolve(baseName);
        if (Files.isRegularFile(sh)) return sh.toAbsolutePath().toString();

        return null;
    }
}