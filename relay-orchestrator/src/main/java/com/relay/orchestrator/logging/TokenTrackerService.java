package com.relay.orchestrator.logging;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TokenTrackerService {

    private final List<Map<String, Object>> transactions = new CopyOnWriteArrayList<>();
    private int totalTokens = 0;
    private double totalCost = 0.0;

    /**
     * Legacy in-memory ledger. Cost is estimated using Anthropic Sonnet rates
     * as a reference — Copilot is a subscription, but the estimate gives the
     * user a sense of what equivalent API usage would cost.
     */
    public synchronized void logUsage(String agentName, String model, int inputTokens, int outputTokens) {
        int totalRunTokens = inputTokens + outputTokens;

        double inputCost = (inputTokens / 1_000_000.0) * 3.0;
        double outputCost = (outputTokens / 1_000_000.0) * 15.0;
        double runCost = inputCost + outputCost;

        this.totalTokens += totalRunTokens;
        this.totalCost += runCost;

        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("timestamp", LocalDateTime.now());
        tx.put("agentName", agentName);
        tx.put("model", model);
        tx.put("tokensUsed", totalRunTokens);
        tx.put("costUsd", runCost);

        transactions.add(0, tx);
    }

    public List<Map<String, Object>> getTransactions() {
        return transactions;
    }

    public synchronized int getTotalTokens() {
        return totalTokens;
    }

    public synchronized double getTotalCost() {
        return totalCost;
    }
}