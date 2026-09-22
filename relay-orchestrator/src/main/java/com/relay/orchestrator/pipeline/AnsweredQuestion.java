package com.relay.orchestrator.pipeline;

/**
 * One answered clarifying question, accumulated across turns.
 * Sent back to the LLM as context on subsequent turns.
 */
public record AnsweredQuestion(
        String questionId,
        String question,
        String why,
        String answer) {
}