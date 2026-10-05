package com.relay.orchestrator.pipeline;

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

    /** Implementer failed to produce a diff. Terminal. */
    IMPLEMENTATION_FAILED,

    /** Compile gate failed after retry. Terminal. */
    COMPILE_FAILED,

    /** Clarifier produced BLOCKED; terminal state. */
    BLOCKED,

    /** Turn cap reached without reaching a terminal state. */
    CAPPED,

    /** Diff has been written to disk. */
    APPLIED,

    /** Unrecoverable error. Terminal. */
    ERROR;

    public boolean isTerminal() {
        return this == BLOCKED || this == CAPPED || this == ERROR
                || this == IMPLEMENTATION_FAILED
                || this == COMPILE_FAILED;
    }

    public boolean isAwaitingUser() {
        return this == AWAITING_ANSWERS;
    }
}