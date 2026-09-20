package com.relay.orchestrator.connection;

import java.time.LocalDateTime;

public record ConnectionStatus(Status status, LocalDateTime timestamp, String provider) {
    public enum Status {
        SUCCESS,
        FAILED,
        NOT_TESTED
    }
}
