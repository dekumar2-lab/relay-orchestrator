package com.relay.orchestrator.connection;

public interface LlmConnectionChecker {
    void validate(ConnectionConfig config);
}
