package com.relay.orchestrator.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.relay.orchestrator.connection.ConnectionConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class AppConfigManager {

    private static final Logger log = LoggerFactory.getLogger(AppConfigManager.class);

    // Prefer the project-local config file, then fall back to
    // ~/.relay-orchestrator/config.yml
    private final Path configPath = resolveConfigPath();

    private static Path resolveConfigPath() {
        Path projectConfig = Paths.get("config.yml").toAbsolutePath().normalize();
        if (Files.exists(projectConfig)) {
            return projectConfig;
        }
        Path homeConfig = Paths.get(System.getProperty("user.home"), ".relay-orchestrator", "config.yml");
        return homeConfig;
    }

    // Loaded operational values stored in-memory
    private String githubToken = "";
    private String anthropicApiKey = "";
    private String workspaceDir = "";
    private String model = "claude-sonnet-4-5";
    private int requestBudget = 20;
    private String provider = "GITHUB_COPILOT";
    private final List<RepositoryConfig> repositories = new ArrayList<>();

    @PostConstruct
    public void init() {
        loadSettingsFromDisk();
    }

    /**
     * Reads and parses YAML settings from disk. Falls back safely if missing.
     */
    @SuppressWarnings("unchecked")
    public synchronized void loadSettingsFromDisk() {
        if (!Files.exists(configPath)) {
            log.info("No configuration file found at {}. Initializing defaults.", configPath);
            this.repositories.clear();
            addDefaultBackendRepositoryIfPresent();
            flushSettingsToDisk();
            return;
        }

        try (InputStream input = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(input);
            if (data == null) {
                this.repositories.clear();
                addDefaultBackendRepositoryIfPresent();
                return;
            }

            // Load Phase 1 parameters safely
            this.githubToken = (String) data.getOrDefault("githubToken", "");
            this.anthropicApiKey = (String) data.getOrDefault("anthropicApiKey", "");
            this.workspaceDir = (String) data.getOrDefault("workspaceDir", "");
            this.model = (String) data.getOrDefault("model", "claude-sonnet-4-5");
            this.provider = String.valueOf(data.getOrDefault("provider", "GITHUB_COPILOT"));
            this.requestBudget = (Integer) data.getOrDefault("requestBudget", 20);

            // Load and transform Phase 2 repositories list mapping arrays
            this.repositories.clear();
            List<Map<String, Object>> repoList = (List<Map<String, Object>>) data.get("repositories");
            if (repoList != null) {
                for (Map<String, Object> repoMap : repoList) {
                    RepositoryConfig repo = new RepositoryConfig();
                    repo.setId((String) repoMap.get("id"));
                    repo.setPath((String) repoMap.get("path"));

                    if (repoMap.get("sourceType") != null) {
                        repo.setSourceType(SourceType.valueOf((String) repoMap.get("sourceType")));
                    }
                    if (repoMap.get("status") != null) {
                        repo.setStatus(RepoStatus.valueOf((String) repoMap.get("status")));
                    }
                    if (repoMap.get("lastIndexed") != null) {
                        repo.setLastIndexed(LocalDateTime.parse((String) repoMap.get("lastIndexed"),
                                DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    this.repositories.add(repo);
                }
            }
            if (this.repositories.isEmpty()) {
                addDefaultBackendRepositoryIfPresent();
            }
            log.info("Successfully loaded {} repositories from settings config.", repositories.size());
        } catch (Exception e) {
            log.error("Failed to safely read config.yml file from folder context structure", e);
        }
    }

    private void addDefaultBackendRepositoryIfPresent() {
        Path backendDir = Paths.get(System.getProperty("user.dir")).resolve("../backend").normalize();
        if (!Files.isDirectory(backendDir)) {
            return;
        }

        for (RepositoryConfig repo : this.repositories) {
            if ("backend".equals(repo.getId())) {
                return;
            }
        }

        RepositoryConfig repo = new RepositoryConfig();
        repo.setId("backend");
        repo.setPath(backendDir.toString());
        repo.setSourceType(SourceType.LOCAL_PATH);
        repo.setStatus(RepoStatus.NOT_INDEXED);
        this.repositories.add(repo);
    }

    /**
     * Serializes the current in-memory configurations directly into structured YAML
     * markup.
     */
    public synchronized void flushSettingsToDisk() {
        try {
            // Ensure parent directory infrastructure exists securely
            Files.createDirectories(configPath.getParent());

            Map<String, Object> rawData = new LinkedHashMap<>();
            rawData.put("provider", this.provider);
            rawData.put("githubToken", this.githubToken);
            rawData.put("anthropicApiKey", this.anthropicApiKey);
            rawData.put("workspaceDir", this.workspaceDir);
            rawData.put("model", this.model);
            rawData.put("requestBudget", this.requestBudget);

            // Structure our sub-nodes array maps objects
            List<Map<String, Object>> repoListMaps = new ArrayList<>();
            for (RepositoryConfig repo : this.repositories) {
                Map<String, Object> rMap = new LinkedHashMap<>();
                rMap.put("id", repo.getId());
                rMap.put("path", repo.getPath());
                rMap.put("sourceType", repo.getSourceType().name());
                rMap.put("status", repo.getStatus().name());
                rMap.put("lastIndexed",
                        repo.getLastIndexed() != null
                                ? repo.getLastIndexed().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                : null);
                repoListMaps.add(rMap);
            }
            rawData.put("repositories", repoListMaps);

            // Configure pretty printing styling formatting output constraints
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml yaml = new Yaml(options);

            try (FileWriter writer = new FileWriter(configPath.toFile())) {
                yaml.dump(rawData, writer);
            }
            log.debug("Successfully saved state alterations back to config.yml file.");
        } catch (Exception e) {
            log.error("Critical error encountered writing application property updates back to disk targets", e);
        }
    }

        /**
     * Snapshot of the connection-related settings as a ConnectionConfig,
     * for use by the connection checkers and the Settings form.
     */
    public ConnectionConfig toConnectionConfig() {
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setProvider(parseProvider(this.provider));
        cfg.setGithubToken(this.githubToken);
        cfg.setAnthropicApiKey(this.anthropicApiKey);
        cfg.setModel(this.model);
        cfg.setWorkspaceDir(this.workspaceDir);
        cfg.setRequestBudget(this.requestBudget);
        return cfg;
    }

    /**
     * Merge a ConnectionConfig back into the manager and persist.
     * Repositories are untouched.
     */
    public synchronized void updateFrom(ConnectionConfig cfg) {
        this.provider = cfg.getProvider().name();
        this.githubToken = cfg.getGithubToken();
        this.anthropicApiKey = cfg.getAnthropicApiKey();
        this.model = cfg.getModel();
        this.workspaceDir = cfg.getWorkspaceDir();
        this.requestBudget = cfg.getRequestBudget();
        flushSettingsToDisk();
    }

    private ConnectionConfig.Provider parseProvider(String raw) {
        try {
            return ConnectionConfig.Provider.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ConnectionConfig.Provider.GITHUB_COPILOT;
        }
    }

    // --- Accessor Getter and Setter Methods ---
    public String getGithubToken() {
        return githubToken;
    }

    public void setGithubToken(String token) {
        this.githubToken = token;
    }

    public String getAnthropicApiKey() {
        return anthropicApiKey;
    }

    public void setAnthropicApiKey(String anthropicApiKey) {
        this.anthropicApiKey = anthropicApiKey;
    }

    public String getWorkspaceDir() {
        return workspaceDir;
    }

    public void setWorkspaceDir(String dir) {
        this.workspaceDir = dir;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getRequestBudget() {
        return requestBudget;
    }

    public void setRequestBudget(int budget) {
        this.requestBudget = budget;
    }

    public List<RepositoryConfig> getRepositories() {
        return repositories;
    }
}
