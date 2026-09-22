package com.relay.orchestrator.service;

import java.util.List;

/**
 * One question the clarifier needs answered before it can proceed.
 */
public record ClarifyingQuestion(
        String id,
        String question,
        String why,
        List<String> options) {
}