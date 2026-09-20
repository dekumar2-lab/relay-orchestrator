package com.relay.orchestrator.connection;

import com.relay.orchestrator.config.AppConfigManager; // Presumed existing manager
import com.relay.orchestrator.config.RepositoryConfig;
import com.relay.orchestrator.config.RepoStatus;
import com.relay.orchestrator.index.RepoIndexerService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/repositories")
public class RepositoryController {

    private final AppConfigManager configManager;
    private final RepoIndexerService indexerService;

    public RepositoryController(AppConfigManager configManager, RepoIndexerService indexerService) {
        this.configManager = configManager;
        this.indexerService = indexerService;
    }

    @GetMapping
    public String viewRepositoriesScreen(Model model) {
        for (RepositoryConfig repo : configManager.getRepositories()) {
            if (repo.getStatus() == RepoStatus.INDEXED && repo.getLastIndexed() != null) {
                if (isRepositoryDirectoryStale(repo)) {
                    repo.setStatus(RepoStatus.STALE);
                }
            }
        }
        model.addAttribute("activeTab", "repositories");
        model.addAttribute("viewContent", "repositories");
        model.addAttribute("repositories", configManager.getRepositories());
        return "layout";
    }

    @PostMapping("/add")
    public String addRepositoryEntry(@RequestParam String id, @RequestParam String path, Model model) {
        File folder = new File(path);
        boolean hasPom = new File(folder, "pom.xml").exists();
        boolean hasGradle = new File(folder, "build.gradle").exists();

        // Server side layout format validations
        if (!folder.exists() || !folder.isDirectory() || (!hasPom && !hasGradle)) {
            model.addAttribute("activeTab", "repositories");
            model.addAttribute("viewContent", "repositories");
            model.addAttribute("error",
                    "The specified target directory must exist locally and contain a valid pom.xml or build.gradle orchestration manifest file.");
            model.addAttribute("repositories", configManager.getRepositories());
            return "layout";
        }

        RepositoryConfig newRepo = new RepositoryConfig();
        newRepo.setId(id.replaceAll("[^a-zA-Z0-9\\-_]", ""));
        newRepo.setPath(path);

        configManager.getRepositories().add(newRepo);
        configManager.flushSettingsToDisk(); // Serializes current stack data back to configuration YAML

        return "redirect:/repositories";
    }

    @PostMapping("/build/{id}")
    @ResponseBody
    public ResponseEntity<?> triggerSingleIndexRun(@PathVariable String id) {
        RepositoryConfig repo = findRepoById(id);
        if (repo == null)
            return ResponseEntity.badRequest().body("Repository not found");

        try {
            indexerService.indexRepository(repo.getId(), Paths.get(repo.getPath()));
            repo.setStatus(RepoStatus.INDEXED);
            repo.setLastIndexed(LocalDateTime.now());
        } catch (Exception e) {
            repo.setStatus(RepoStatus.FAILED);
        } finally {
            configManager.flushSettingsToDisk();
        }

        return ResponseEntity.ok()
                .header("HX-Refresh", "true")
                .body(Map.of("status", repo.getStatus().name()));
    }

    @PostMapping("/build-all")
    @ResponseBody
    public ResponseEntity<?> triggerBuildAllSequence() {
        try {
            for (RepositoryConfig repo : configManager.getRepositories()) {
                if (repo.getStatus() == RepoStatus.NOT_INDEXED || repo.getStatus() == RepoStatus.STALE) {
                    try {
                        indexerService.indexRepository(repo.getId(), Paths.get(repo.getPath()));
                        repo.setStatus(RepoStatus.INDEXED);
                        repo.setLastIndexed(LocalDateTime.now());
                    } catch (Exception e) {
                        repo.setStatus(RepoStatus.FAILED);
                    }
                }
            }
        } finally {
            configManager.flushSettingsToDisk();
        }
        return ResponseEntity.ok()
                .header("HX-Refresh", "true")
                .body(Map.of("status", "complete"));
    }

    private RepositoryConfig findRepoById(String id) {
        return configManager.getRepositories().stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
    }

    private boolean isRepositoryDirectoryStale(RepositoryConfig repo) {
        // Walk file trees up to find modifications newer than the last indexed baseline
        try {
            long lastIndexedEpoch = repo.getLastIndexed().atZone(java.time.ZoneId.systemDefault()).toInstant()
                    .toEpochMilli();
            return Files.walk(Paths.get(repo.getPath()))
                    .filter(p -> p.toString().endsWith(".java"))
                    .mapToLong(p -> p.toFile().lastModified())
                    .max().orElse(0L) > lastIndexedEpoch;
        } catch (Exception e) {
            return false;
        }
    }
}
