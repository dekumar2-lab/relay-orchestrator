package com.relay.orchestrator.pipeline;

/**
 * Stages of the pipeline state machine. Each maps to a node in the
 * eventual LangGraph4j graph. Today we dispatch by hand.
 */
public enum PipelineStage {

    /** Initial state on session creation, before the first clarifier call. */
    NEW,

    /** Clarifier has produced NEEDS_INPUT; we are waiting for the user. */
    AWAITING_ANSWERS,

    /** Clarifier produced READY; the session can proceed to implementation. */
    READY_TO_IMPLEMENT,

    /** Implementer loop is running for this session. */
    IMPLEMENTATION_RUNNING,

    /** Implementer produced a diff ready for user review. */
    IMPLEMENTATION_READY,

    /** Implementer failed. Terminal. */
    IMPLEMENTATION_FAILED,

    /** Clarifier produced BLOCKED; terminal state. */
    BLOCKED,

    /** Turn cap reached without reaching a terminal state. */
    CAPPED,

    /** Unrecoverable error. Terminal. */
    ERROR;

    public boolean isTerminal() {
        return this == BLOCKED || this == CAPPED || this == ERROR
                || this == IMPLEMENTATION_FAILED;
    }

    public boolean isAwaitingUser() {
        return this == AWAITING_ANSWERS;
    }
}