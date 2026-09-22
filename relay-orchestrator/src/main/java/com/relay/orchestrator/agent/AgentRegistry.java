package com.relay.orchestrator.agent;

import com.relay.orchestrator.config.AppConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads personas from disk (user dir overrides classpath) and resolves
 * AgentRole -> AgentPersona using the config.yml mapping.
 *
 * Nothing here hardcodes persona names. "michael", "dwight" etc. only appear
 * in config.yml and in the persona markdown files themselves.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final AppConfigManager appConfig;
    private final ResourceLoader resources;
    private final Map<String, AgentPersona> cache = new ConcurrentHashMap<>();

    public AgentRegistry(AppConfigManager appConfig, ResourceLoader resources) {
        this.appConfig = appConfig;
        this.resources = resources;
        log.info("AgentRegistry created (config will be read on first use)");
    }

    /** Resolve the persona that fills a role, or a generic fallback. */
    public AgentPersona getForRole(AgentRole role) {
        AgentConfig cfg = appConfig.getAgentConfig();
         log.info("getForRole({}): enabled={} mapped={}",
                role, cfg.enabled(), cfg.personaFor(role));

        if (!cfg.enabled()) {
            return AgentPersona.generic(role.name());
        }

        Optional<String> personaName = cfg.personaFor(role);
        if (personaName.isEmpty()) {
            return AgentPersona.generic(role.name());
        }

        Optional<AgentPersona> loaded = load(personaName.get());
        return loaded.orElseGet(() -> {
            log.warn("Persona '{}' for role {} not found, using generic fallback",
                    personaName.get(), role);
            return AgentPersona.generic(role.name());
        });
    }

    /** Load a persona by folder name. Empty if not found anywhere. */
    public Optional<AgentPersona> load(String personaName) {
        return Optional.ofNullable(cache.computeIfAbsent(personaName, this::readPersona));
    }

    /** Every persona folder the registry can see on disk. Powers the UI dropdown. */
    public List<String> listAvailable() {
        List<String> names = new ArrayList<>();
        AgentConfig cfg = appConfig.getAgentConfig();

        for (String searchPath : cfg.searchPaths()) {
            if (searchPath.startsWith("classpath:")) {
                continue; // can't enumerate classpath dirs portably
            }
            Path dir = Paths.get(expandHome(searchPath));
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> !names.contains(name))
                        .forEach(names::add);
            } catch (IOException e) {
                log.warn("Failed to list personas in {}", dir, e);
            }
        }
        return names;
    }

    /** Invalidate cached personas (called when config changes). */
    public void invalidate() {
        cache.clear();
    }

    private AgentPersona readPersona(String personaName) {
        AgentConfig cfg = appConfig.getAgentConfig();

        for (String searchPath : cfg.searchPaths()) {
            Optional<String> identity = readFile(searchPath, personaName, "IDENTITY.md");
            Optional<String> soul = readFile(searchPath, personaName, "SOUL.md");
            Optional<String> role = readFile(searchPath, personaName, "ROLE.md");

            if (identity.isPresent() && soul.isPresent() && role.isPresent()) {
                AgentConfig.PersonaOverride override = cfg.overrideFor(personaName)
                        .orElse(new AgentConfig.PersonaOverride(
                                Optional.empty(), Optional.empty(), Optional.empty()));

                String displayName = extractDisplayName(identity.get(), personaName);
                log.debug("Loaded persona '{}' from {}", personaName, searchPath);
                return new AgentPersona(
                        personaName, displayName,
                        identity.get(), soul.get(), role.get(),
                        override.model(),
                        override.temperature(),
                        override.maxTokens());
            }
        }
        return null;
    }

    private Optional<String> readFile(String base, String folder, String file) {
        try {
            if (base.startsWith("classpath:")) {
                String path = base + "/" + folder + "/" + file;
                Resource r = resources.getResource(path);
                if (!r.exists()) return Optional.empty();
                return Optional.of(r.getContentAsString(StandardCharsets.UTF_8));
            } else {
                Path p = Paths.get(expandHome(base), folder, file);
                if (!Files.isRegularFile(p)) return Optional.empty();
                return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private String extractDisplayName(String identityMarkdown, String fallback) {
        for (String line : identityMarkdown.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("# ")) return t.substring(2).trim();
        }
        return fallback;
    }

    private String expandHome(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}