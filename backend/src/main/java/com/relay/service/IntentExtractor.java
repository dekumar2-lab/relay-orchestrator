package com.relay.service;

import com.relay.agent.RelayOrchestratorAgent;
import com.relay.model.IntentTokens;
import org.springframework.stereotype.Service;

@Service
public class IntentExtractor {

    private final RelayOrchestratorAgent agent;

    public IntentExtractor(RelayOrchestratorAgent agent) {
        this.agent = agent;
    }

    /**
     * Uses the LLM to extract structured intent from the story.
     * For the hackathon, we do a simple keyword-based extraction.
     * The LLM call can be enabled later.
     */
    public IntentTokens extract(String story) {
        IntentTokens tokens = new IntentTokens();
        tokens.setAction("MODIFY");

        String lower = story.toLowerCase();

        if (lower.contains("cui") || lower.contains("workbasket")) {
            tokens.setProject("cui");
            tokens.setTargetClass("WorkbasketComponent");
        } else if (lower.contains("payment")) {
            tokens.setProject("payment-service");
            tokens.setTargetClass("PaymentService");
        } else {
            tokens.setProject("unknown");
            tokens.setTargetClass("unknown");
        }

        tokens.getKeywords().add("navigate");
        tokens.getKeywords().add("method");
        tokens.getKeywords().add("template");

        if (lower.contains("no test") || lower.contains("do not add test")) {
            tokens.getConstraints().put("skipTests", true);
        }
        if (lower.contains("technical design") || lower.contains("design also")) {
            tokens.getConstraints().put("generateDesign", true);
        }

        return tokens;
    }
}