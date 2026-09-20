package com.relay.orchestrator.logging;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupLogger implements ApplicationRunner {

    private final LogBroadcaster broadcaster;

    public StartupLogger(LogBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void run(ApplicationArguments args) {
        broadcaster.publish(LogEvent.success("Relay Orchestrator started - fill in the Connection form and click Test Connection"));
    }
}
