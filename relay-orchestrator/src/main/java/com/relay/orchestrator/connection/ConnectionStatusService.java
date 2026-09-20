package com.relay.orchestrator.connection;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ConnectionStatusService {

    private final AtomicReference<ConnectionStatus> latest = new AtomicReference<>(
            new ConnectionStatus(ConnectionStatus.Status.NOT_TESTED, LocalDateTime.now(), "GITHUB_COPILOT"));

    public ConnectionStatus getLatest() {
        return latest.get();
    }

    public void markSuccess(String provider) {
        latest.set(new ConnectionStatus(ConnectionStatus.Status.SUCCESS, LocalDateTime.now(), provider));
    }

    public void markFailure(String provider) {
        latest.set(new ConnectionStatus(ConnectionStatus.Status.FAILED, LocalDateTime.now(), provider));
    }

    public void markNotTested() {
        latest.set(new ConnectionStatus(ConnectionStatus.Status.NOT_TESTED, LocalDateTime.now(), "GITHUB_COPILOT"));
    }
}
