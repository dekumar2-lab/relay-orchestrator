package com.relay.orchestrator.agent;

import java.util.Optional;

/**
 * One loaded persona. Compose the system prompt from identity + soul + role.
 * Optional overrides fall back to the connection config's defaults.
 */
public record AgentPersona(
        String name,
        String displayName,
        String identity,
        String soul,
        String role,
        Optional<String> modelOverride,
        Optional<Double> temperatureOverride,
        Optional<Integer> maxTokensOverride) {

    /** The full system prompt passed to the LLM. */
    public String toSystemPrompt() {
        return identity + "\n\n" + soul + "\n\n" + role;
    }

    /** When no persona is configured or found, fall back to this. */
    public static AgentPersona generic(String roleLabel) {
        return new AgentPersona(
                "generic",
                "Generic " + roleLabel,
                "# Identity\nYou are a senior software engineer acting as a "
                        + roleLabel.toLowerCase() + " in a code orchestration pipeline.",
                "# Voice\nDirect, professional, no filler.",
                "# Role\n" + roleLabel + ": follow the instructions in the user message.",
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}