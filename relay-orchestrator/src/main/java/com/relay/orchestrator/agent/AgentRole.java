package com.relay.orchestrator.agent;

/**
 * Structural roles in the pipeline. Part of the pipeline's shape, not
 * user config. Adding a new role means adding a new pipeline stage.
 * Which persona fills a role is decided in config.yml under agents.roles.
 */
public enum AgentRole {
    ORCHESTRATOR, // clarifier — reads story, asks questions, produces ClarificationResult
    PLANNER, // produces PLAN artifacts (implementation plans)
    IMPLEMENTER, // consumes approved PLAN, produces diffs
    REVIEWER, // produces REVIEW artifacts on diffs
    DOCUMENTER, // produces PR descriptions and docs
    ANALYST, // reserved for exploratory "how does this work" queries
    TESTER // reserved for future test generation
}