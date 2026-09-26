package com.relay.orchestrator.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.relay.orchestrator.agent.AgentConfig;
import com.relay.orchestrator.agent.AgentRole;
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

    private final Path configPath = resolveConfigPath();

    private AgentConfig agentConfig = AgentConfig.defaults();

    public AgentConfig getAgentConfig() {
        return agentConfig;
    }

    private static Path resolveConfigPath() {
        String envDataDir = System.getenv("RELAY_DATA_DIR");
        if (envDataDir != null && !envDataDir.isBlank()) {
            return Path.of(envDataDir, "config.yml");
        }
        Path projectConfig = Paths.get("config.yml").toAbsolutePath().normalize();
        if (Files.exists(projectConfig)) {
            return projectConfig;
        }
        return Paths.get(System.getProperty("user.home"), ".relay-orchestrator", "config.yml");
    }

    private String githubToken = "";
    private String workspaceDir = "";
    private String model = "gpt-4o";
    private int requestBudget = 20;
    private String provider = "GITHUB_COPILOT";
    private final List<RepositoryConfig> repositories = new ArrayList<>();

    @PostConstruct
    public void init() {
        loadSettingsFromDisk();
    }

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

            this.githubToken = (String) data.getOrDefault("githubToken", "");
            this.workspaceDir = (String) data.getOrDefault("workspaceDir", "");
            this.model = (String) data.getOrDefault("model", "gpt-4o");
            this.provider = String.valueOf(data.getOrDefault("provider", "GITHUB_COPILOT"));
            this.requestBudget = (Integer) data.getOrDefault("requestBudget", 20);

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

            this.agentConfig = parseAgentConfig(data.get("agents"));

            log.info("Agent config: enabled={} roles={} searchPaths={}",
                    agentConfig.enabled(),
                    agentConfig.roleToPersona(),
                    agentConfig.searchPaths());

            log.info("Successfully loaded {} repositories from settings config.", repositories.size());
        } catch (Exception e) {
            log.error("Failed to safely read config.yml file from folder context structure", e);
        }
    }

    @SuppressWarnings("unchecked")
    private AgentConfig parseAgentConfig(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return AgentConfig.defaults();
        }
        Map<String, Object> block = (Map<String, Object>) rawMap;

        boolean enabled = Boolean.TRUE.equals(block.get("enabled"));

        List<String> searchPaths = AgentConfig.defaults().searchPaths();
        if (block.get("searchPaths") instanceof List<?> list) {
            searchPaths = list.stream().map(String::valueOf).toList();
        }

        Map<AgentRole, String> roleToPersona = new java.util.EnumMap<>(AgentRole.class);
        if (block.get("roles") instanceof Map<?, ?> roles) {
            for (Map.Entry<?, ?> e : roles.entrySet()) {
                try {
                    AgentRole role = AgentRole.valueOf(String.valueOf(e.getKey()).toUpperCase());
                    roleToPersona.put(role, String.valueOf(e.getValue()));
                } catch (IllegalArgumentException ex) {
                    log.warn("Unknown agent role in config: {}", e.getKey());
                }
            }
        }

        Map<String, AgentConfig.PersonaOverride> overrides = new java.util.HashMap<>();
        if (block.get("overrides") instanceof Map<?, ?> ovr) {
            for (Map.Entry<?, ?> e : ovr.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (e.getValue() instanceof Map<?, ?> m) {
                    overrides.put(name, new AgentConfig.PersonaOverride(
                            Optional.ofNullable(m.get("model")).map(String::valueOf),
                            Optional.ofNullable(m.get("temperature"))
                                    .map(v -> ((Number) v).doubleValue()),
                            Optional.ofNullable(m.get("maxTokens"))
                                    .map(v -> ((Number) v).intValue())));
                }
            }
        }

        Object fallbackRaw = block.get("fallbackPersona");
        String fallback = fallbackRaw != null ? String.valueOf(fallbackRaw) : "generic";

        return new AgentConfig(enabled, searchPaths, roleToPersona, overrides, fallback);
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

    public synchronized void flushSettingsToDisk() {
        try {
            Files.createDirectories(configPath.getParent());

            Map<String, Object> rawData = new LinkedHashMap<>();
            rawData.put("provider", this.provider);
            rawData.put("githubToken", this.githubToken);
            rawData.put("workspaceDir", this.workspaceDir);
            rawData.put("model", this.model);
            rawData.put("requestBudget", this.requestBudget);

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

            Map<String, Object> agentsBlock = new LinkedHashMap<>();
            agentsBlock.put("enabled", this.agentConfig.enabled());
            agentsBlock.put("searchPaths", this.agentConfig.searchPaths());

            Map<String, String> rolesOut = new LinkedHashMap<>();
            this.agentConfig.roleToPersona()
                    .forEach((k, v) -> rolesOut.put(k.name().toLowerCase(), v));
            agentsBlock.put("roles", rolesOut);

            Map<String, Object> overridesOut = new LinkedHashMap<>();
            this.agentConfig.overrides().forEach((name, ovr) -> {
                Map<String, Object> o = new LinkedHashMap<>();
                ovr.model().ifPresent(m -> o.put("model", m));
                ovr.temperature().ifPresent(t -> o.put("temperature", t));
                ovr.maxTokens().ifPresent(t -> o.put("maxTokens", t));
                overridesOut.put(name, o);
            });
            agentsBlock.put("overrides", overridesOut);
            agentsBlock.put("fallbackPersona", this.agentConfig.fallbackPersona());

            rawData.put("agents", agentsBlock);

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

    public ConnectionConfig toConnectionConfig() {
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setProvider(parseProvider(this.provider));
        cfg.setGithubToken(this.githubToken);
        cfg.setModel(this.model);
        cfg.setWorkspaceDir(this.workspaceDir);
        cfg.setRequestBudget(this.requestBudget);
        return cfg;
    }

    /**
     * Null-safe merge. Fields left null/blank on the incoming config are treated
     * as "no change" so a partially-bound settings form can never wipe stored
     * credentials on disk. This is the fix for the split-brain bug where an empty
     * GitHub token field cleared the persisted token on save.
     */
    public synchronized void updateFrom(ConnectionConfig cfg) {
        if (cfg == null) {
            log.warn("updateFrom called with null config; ignoring.");
            return;
        }

        if (cfg.getProvider() != null) {
            this.provider = cfg.getProvider().name();
        }
        if (cfg.getGithubToken() != null && !cfg.getGithubToken().isBlank()) {
            this.githubToken = cfg.getGithubToken();
        }
        if (cfg.getModel() != null && !cfg.getModel().isBlank()) {
            this.model = cfg.getModel();
        }
        if (cfg.getWorkspaceDir() != null) {
            this.workspaceDir = cfg.getWorkspaceDir();
        }
        if (cfg.getRequestBudget() > 0) {
            this.requestBudget = cfg.getRequestBudget();
        }

        flushSettingsToDisk();
    }

    private ConnectionConfig.Provider parseProvider(String raw) {
        try {
            return ConnectionConfig.Provider.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ConnectionConfig.Provider.GITHUB_COPILOT;
        }
    }

    public String getGithubToken() {
        return githubToken;
    }

    public void setGithubToken(String token) {
        this.githubToken = token;
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