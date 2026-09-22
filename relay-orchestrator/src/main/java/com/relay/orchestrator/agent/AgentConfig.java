package com.relay.orchestrator.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed view of the agents: block in config.yml.
 * All fields have sane defaults so that omitting the block entirely works.
 */
public record AgentConfig(
        boolean enabled,
        List<String> searchPaths,
        Map<AgentRole, String> roleToPersona,
        Map<String, PersonaOverride> overrides,
        String fallbackPersona) {

    public static AgentConfig defaults() {
        return new AgentConfig(
                false,
                List.of("~/.relay-orchestrator/agents", "classpath:agents"),
                Map.of(),
                Map.of(),
                "generic");
    }

    public Optional<String> personaFor(AgentRole role) {
        return Optional.ofNullable(roleToPersona.get(role));
    }

    public Optional<PersonaOverride> overrideFor(String personaName) {
        return Optional.ofNullable(overrides.get(personaName));
    }

    public record PersonaOverride(
            Optional<String> model,
            Optional<Double> temperature,
            Optional<Integer> maxTokens) {
    }
}