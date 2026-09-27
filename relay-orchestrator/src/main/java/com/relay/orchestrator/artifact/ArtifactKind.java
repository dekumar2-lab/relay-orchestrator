package com.relay.orchestrator.artifact;

/**
 * What type of document an artifact holds.
 * Each kind has a dedicated Producer that generates it.
 *
 * The set is fixed in code — adding a kind means writing a new Producer
 * class. Config determines which persona fills each kind, not which
 * kinds exist.
 */
public enum ArtifactKind {
    PLAN, // implementation plan (PLANNER role)
    DESIGN, // architecture/design doc (PLANNER role)
    RCA, // root cause analysis (REVIEWER role)
    REVIEW // code review of a proposed diff (REVIEWER role)
}