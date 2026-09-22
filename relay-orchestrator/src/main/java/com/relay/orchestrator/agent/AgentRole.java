package com.relay.orchestrator.agent;

/**
 * Structural roles in the pipeline. Part of the pipeline's shape, not
 * user config. Adding a new role means adding a new pipeline stage.
 * Which persona fills a role is decided in config.yml under agents.roles.
 */
public enum AgentRole {
    ORCHESTRATOR,
    ANALYST,
    IMPLEMENTER,
    TESTER,
    DOCUMENTER
}