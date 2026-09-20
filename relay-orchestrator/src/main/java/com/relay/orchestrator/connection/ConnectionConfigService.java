package com.relay.orchestrator.connection;

import com.relay.orchestrator.config.AppConfigManager;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Thin facade over AppConfigManager so that the connection layer can keep
 * its existing call sites (load/save) without duplicating file I/O.
 *
 * The single source of truth for config.yml is AppConfigManager.
 */
@Service
public class ConnectionConfigService {

    private final AppConfigManager appConfigManager;

    public ConnectionConfigService(AppConfigManager appConfigManager) {
        this.appConfigManager = appConfigManager;
    }

    public Optional<ConnectionConfig> load() {
        return Optional.of(appConfigManager.toConnectionConfig());
    }

    public void save(ConnectionConfig config) {
        appConfigManager.updateFrom(config);
    }
}