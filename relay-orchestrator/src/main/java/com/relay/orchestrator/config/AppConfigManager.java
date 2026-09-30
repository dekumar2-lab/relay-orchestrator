package com.relay.orchestrator.config;

import com.relay.orchestrator.agent.AgentConfig;
import com.relay.orchestrator.agent.AgentRole;
import com.relay.orchestrator.connection.ConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class AppConfigManager {

    private static final Logger log = LoggerFactory.getLogger(AppConfigManager.class);

    private final Path configPath;
    private final ApplicationEventPublisher events;
    private final List<RepositoryConfig> repositories = new ArrayList<>();

    private AgentConfig agentConfig = AgentConfig.defaults();
    private String githubToken = "";
    private String workspaceDir = "";
    private String model = "gpt-4o";
    private int requestBudget = 20;
    private String provider = "GITHUB_COPILOT";

    @Autowired
    public AppConfigManager(ApplicationEventPublisher events) {
        this(resolveConfigPath(), events);
    }

    /** Package-private for tests. */
    AppConfigManager(Path configPath, ApplicationEventPublisher events) {
        this.configPath = configPath;
        this.events = events;
    }

    private static Path resolveConfigPath() {
        String envDataDir = System.getenv("RELAY_DATA_DIR");
        if (envDataDir != null && !envDataDir.isBlank()) {
            return Path.of(envDataDir, "config.yml");
        }
        Path projectConfig = Paths.get("config.yml").toAbsolutePath().normalize();
        if (Files.exists(projectConfig)) return projectConfig;
        return Paths.get(System.getProperty("user.home"), ".relay-orchestrator", "config.yml");
    }

    @PostConstruct
    public void init() {
        loadSettingsFromDisk();
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadSettingsFromDisk() {
        if (!Files.exists(configPath)) {
            log.info("No configuration file at {}. Initializing defaults.", configPath);
            this.repositories.clear();
            addDefaultBackendRepositoryIfPresent();
            flushSettingsToDisk();
            return;
        }

        try (InputStream input = Files.newInputStream(configPath)) {
            Map<String, Object> data = new Yaml().load(input);
            if (data == null) {
                this.repositories.clear();
                addDefaultBackendRepositoryIfPresent();
                return;
            }

            // YAML `key:` (empty value) yields a null in the map. getOrDefault does
            // NOT substitute the default when the key exists with null. Route every
            // scalar through str()/intOr().
            this.githubToken   = str(data.get("githubToken"), "");
            this.workspaceDir  = str(data.get("workspaceDir"), "");
            this.model         = str(data.get("model"), "gpt-4o");
            this.provider      = str(data.get("provider"), "GITHUB_COPILOT");
            this.requestBudget = intOr(data.get("requestBudget"), 20);

            this.repositories.clear();
            List<Map<String, Object>> repoList = (List<Map<String, Object>>) data.get("repositories");
            if (repoList != null) {
                for (Map<String, Object> repoMap : repoList) {
                    RepositoryConfig repo = new RepositoryConfig();
                    repo.setId(str(repoMap.get("id"), null));
                    repo.setPath(str(repoMap.get("path"), null));

                    String st = str(repoMap.get("sourceType"), null);
                    if (st != null) repo.setSourceType(SourceType.valueOf(st));

                    String rs = str(repoMap.get("status"), null);
                    if (rs != null) repo.setStatus(RepoStatus.valueOf(rs));

                    String li = str(repoMap.get("lastIndexed"), null);
                    if (li != null) {
                        repo.setLastIndexed(LocalDateTime.parse(li,
                                DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    this.repositories.add(repo);
                }
            }
            if (this.repositories.isEmpty()) addDefaultBackendRepositoryIfPresent();

            this.agentConfig = parseAgentConfig(data.get("agents"));

            log.info("Agent config: enabled={} roles={} searchPaths={}",
                    agentConfig.enabled(), agentConfig.roleToPersona(), agentConfig.searchPaths());
            log.info("Loaded {} repositories from config.", repositories.size());
        } catch (Exception e) {
            log.error("Failed to load config from {}", configPath, e);
        }
    }

    private static String str(Object v, String fallback) {
        if (v == null) return fallback;
        String s = String.valueOf(v);
        return s.isEmpty() ? fallback : s;
    }

    private static int intOr(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    @SuppressWarnings("unchecked")
    private AgentConfig parseAgentConfig(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) return AgentConfig.defaults();
        Map<String, Object> block = (Map<String, Object>) rawMap;

        boolean enabled = Boolean.TRUE.equals(block.get("enabled"));

        List<String> searchPaths = AgentConfig.defaults().searchPaths();
        if (block.get("searchPaths") instanceof List<?> list) {
            searchPaths = list.stream().map(String::valueOf).toList();
        }

        Map<AgentRole, String> roleToPersona = new EnumMap<>(AgentRole.class);
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

        Map<String, AgentConfig.PersonaOverride> overrides = new HashMap<>();
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

        String fallback = str(block.get("fallbackPersona"), "generic");
        return new AgentConfig(enabled, searchPaths, roleToPersona, overrides, fallback);
    }

    private void addDefaultBackendRepositoryIfPresent() {
        Path backendDir = Paths.get(System.getProperty("user.dir")).resolve("../backend").normalize();
        if (!Files.isDirectory(backendDir)) return;
        for (RepositoryConfig repo : this.repositories) {
            if ("backend".equals(repo.getId())) return;
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

            try (FileWriter writer = new FileWriter(configPath.toFile())) {
                new Yaml(options).dump(rawData, writer);
            }
            log.debug("Saved config to {}", configPath);
        } catch (Exception e) {
            log.error("Failed to write config to {}", configPath, e);
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

    public synchronized void updateFrom(ConnectionConfig cfg) {
        if (cfg == null) {
            log.warn("updateFrom called with null config; ignoring.");
            return;
        }
        if (cfg.getProvider() != null) this.provider = cfg.getProvider().name();
        if (cfg.getGithubToken() != null && !cfg.getGithubToken().isBlank()) {
            this.githubToken = cfg.getGithubToken();
        }
        if (cfg.getModel() != null && !cfg.getModel().isBlank()) this.model = cfg.getModel();
        if (cfg.getWorkspaceDir() != null && !cfg.getWorkspaceDir().isBlank()) {
            this.workspaceDir = cfg.getWorkspaceDir();
        }
        if (cfg.getRequestBudget() > 0) this.requestBudget = cfg.getRequestBudget();

        flushSettingsToDisk();
        events.publishEvent(new ConfigChangedEvent());
    }

    private ConnectionConfig.Provider parseProvider(String raw) {
        try { return ConnectionConfig.Provider.valueOf(raw); }
        catch (IllegalArgumentException | NullPointerException e) {
            return ConnectionConfig.Provider.GITHUB_COPILOT;
        }
    }

    public AgentConfig getAgentConfig() { return agentConfig; }
    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String token) { this.githubToken = token; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String dir) { this.workspaceDir = dir; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int getRequestBudget() { return requestBudget; }
    public void setRequestBudget(int budget) { this.requestBudget = budget; }
    public List<RepositoryConfig> getRepositories() { return repositories; }
}