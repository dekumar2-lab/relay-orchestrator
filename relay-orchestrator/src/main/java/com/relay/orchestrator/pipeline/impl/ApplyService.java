package com.relay.orchestrator.pipeline.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.orchestrator.config.AppConfigManager;
import com.relay.orchestrator.config.RepoStatus;
import com.relay.orchestrator.config.RepositoryConfig;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.pipeline.PipelineSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);
    private static final String BACKUP_DIR = ".relay-backup";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AppConfigManager configManager;
    private final LogBroadcaster logBroadcaster;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApplyService(AppConfigManager configManager,
            LogBroadcaster logBroadcaster) {
        this.configManager = configManager;
        this.logBroadcaster = logBroadcaster;
    }

    // ----------------------------------------------------------------
    // Apply
    // ----------------------------------------------------------------

    public ApplyResult apply(PipelineSession session) {
        ImplementationResult impl = session.implementationResult();
        if (impl == null || impl.files() == null || impl.files().isEmpty()) {
            return ApplyResult.fail("No diff to apply");
        }

        RepositoryConfig repo = pickWorkingRepo();
        if (repo == null)
            return ApplyResult.fail("No indexed repository available");
        Path repoRoot = Paths.get(repo.getPath()).toAbsolutePath().normalize();

        String timestamp = LocalDateTime.now().format(TS);
        Path backupDir = repoRoot.resolve(BACKUP_DIR)
                .resolve(session.id())
                .resolve(timestamp);
        Path filesBackup = backupDir.resolve("files");

        try {
            Files.createDirectories(filesBackup);
        } catch (IOException e) {
            return ApplyResult.fail("Cannot create backup dir: " + e.getMessage());
        }

        List<Map<String, Object>> manifest = new ArrayList<>();

        for (ImplementationResult.FileDiff diff : impl.files()) {
            // Guard against legacy artifacts written before beforeContent/afterContent
            // existed
            boolean isDelete = "DELETE".equals(diff.changeKind());
            if (!isDelete && diff.afterContent() == null) {
                return ApplyResult.fail(
                        "Diff for " + diff.path() + " is missing content. "
                                + "This session was implemented before the apply feature existed. "
                                + "Re-run the implementer to regenerate the diff.");
            }
            if (diff.beforeContent() == null && Files.exists(
                    repoRoot.resolve(diff.path()).normalize())) {
                return ApplyResult.fail(
                        "Diff for " + diff.path() + " is missing prior content. "
                                + "Re-run the implementer to regenerate the diff.");
            }

            Path target = repoRoot.resolve(diff.path()).normalize();
            if (!target.startsWith(repoRoot)) {
                return ApplyResult.fail("Path escapes repo root: " + diff.path());
            }

            boolean existed = Files.exists(target);
            String currentContent = null;
            try {
                if (existed) {
                    currentContent = Files.readString(target, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                return ApplyResult.fail("Cannot read " + diff.path() + ": " + e.getMessage());
            }

            // Optimistic concurrency check: current disk content must match
            // what the implementer saw when it generated the diff.
            String expectedBefore = diff.beforeContent();
            if (!Objects.equals(currentContent, expectedBefore)) {
                return ApplyResult.fail("File changed on disk since diff was created: "
                        + diff.path());
            }

            // Back up original content
            try {
                if (existed) {
                    Path backupFile = filesBackup.resolve(diff.path());
                    Files.createDirectories(backupFile.getParent());
                    Files.copy(target, backupFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                return ApplyResult.fail("Cannot back up " + diff.path() + ": " + e.getMessage());
            }

            manifest.add(Map.of(
                    "path", diff.path(),
                    "existed", existed,
                    "changeKind", diff.changeKind()));

            // Apply
            try {
                if ("DELETE".equals(diff.changeKind())) {
                    Files.deleteIfExists(target);
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.writeString(target, diff.afterContent(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                return ApplyResult.fail("Cannot write " + diff.path() + ": " + e.getMessage());
            }

            logBroadcaster.publish(LogEvent.info(
                    "[APPLY] " + diff.changeKind() + " " + diff.path()));
        }

        // Write manifest
        try {
            mapper.writeValue(backupDir.resolve("manifest.json").toFile(), manifest);
        } catch (IOException e) {
            log.warn("Manifest write failed: {}", e.getMessage());
        }

        return ApplyResult.ok(backupDir.toString(), impl.files().size());
    }

    // ----------------------------------------------------------------
    // Undo
    // ----------------------------------------------------------------

    public ApplyResult undo(PipelineSession session) {
        RepositoryConfig repo = pickWorkingRepo();
        if (repo == null)
            return ApplyResult.fail("No indexed repository available");
        Path repoRoot = Paths.get(repo.getPath()).toAbsolutePath().normalize();

        Path sessionBackups = repoRoot.resolve(BACKUP_DIR).resolve(session.id());
        if (!Files.isDirectory(sessionBackups)) {
            return ApplyResult.fail("No backups found for this session");
        }

        Path latest;
        try (var stream = Files.list(sessionBackups)) {
            latest = stream
                    .filter(Files::isDirectory)
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return ApplyResult.fail("Cannot list backups: " + e.getMessage());
        }
        if (latest == null)
            return ApplyResult.fail("No backups found for this session");

        Path manifestPath = latest.resolve("manifest.json");
        if (!Files.exists(manifestPath)) {
            return ApplyResult.fail("Manifest missing in " + latest);
        }

        List<Map<String, Object>> manifest;
        try {
            manifest = mapper.readValue(manifestPath.toFile(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (IOException e) {
            return ApplyResult.fail("Cannot read manifest: " + e.getMessage());
        }

        int restored = 0;
        for (Map<String, Object> entry : manifest) {
            String relPath = (String) entry.get("path");
            boolean existed = Boolean.TRUE.equals(entry.get("existed"));

            Path target = repoRoot.resolve(relPath).normalize();
            if (!target.startsWith(repoRoot))
                continue;

            try {
                if (existed) {
                    Path backupFile = latest.resolve("files").resolve(relPath);
                    if (!Files.exists(backupFile)) {
                        log.warn("Backup missing for {}", relPath);
                        continue;
                    }
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.copy(backupFile, target, StandardCopyOption.REPLACE_EXISTING);
                    restored++;
                } else {
                    Files.deleteIfExists(target);
                    restored++;
                }
                logBroadcaster.publish(LogEvent.info("[UNDO] restored " + relPath));
            } catch (IOException e) {
                log.warn("Undo failed for {}: {}", relPath, e.getMessage());
            }
        }

        return ApplyResult.ok(latest.toString(), restored);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private RepositoryConfig pickWorkingRepo() {
        for (RepositoryConfig repo : configManager.getRepositories()) {
            if (repo.getStatus() == RepoStatus.INDEXED
                    || repo.getStatus() == RepoStatus.STALE) {
                return repo;
            }
        }
        return null;
    }
}