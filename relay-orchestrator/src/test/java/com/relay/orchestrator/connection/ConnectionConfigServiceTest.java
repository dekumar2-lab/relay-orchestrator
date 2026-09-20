package com.relay.orchestrator.connection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionConfigServiceTest {

    @Test
    void savesAndLoadsProviderSpecificSettings() {
        ConnectionConfig config = new ConnectionConfig();
        config.setProvider(ConnectionConfig.Provider.CLAUDE_API);
        config.setAnthropicApiKey("test-key");
        config.setModel("claude-sonnet-4-5");

        ConnectionConfig loaded = new ConnectionConfig();
        loaded.setProvider(config.getProvider());
        loaded.setAnthropicApiKey(config.getAnthropicApiKey());
        loaded.setModel(config.getModel());

        assertEquals(ConnectionConfig.Provider.CLAUDE_API, loaded.getProvider());
        assertEquals("test-key", loaded.getAnthropicApiKey());
        assertEquals("claude-sonnet-4-5", loaded.getModel());
    }

    @Test
    void savePreservesExistingRepositories() throws IOException {
        Path tempDir = Files.createTempDirectory("relay-config-test");
        Path originalDir = Path.of(System.getProperty("user.dir"));
        System.setProperty("user.dir", tempDir.toString());
        try {
            Files.writeString(tempDir.resolve("config.yml"), "repositories:\n  - id: demo\n    path: /tmp/demo\n");

            ConnectionConfigService service = new ConnectionConfigService(null);
            ConnectionConfig config = new ConnectionConfig();
            config.setProvider(ConnectionConfig.Provider.GITHUB_COPILOT);
            config.setGithubToken("token");
            config.setAnthropicApiKey("anthropic");
            config.setModel("claude-sonnet-4.5");
            config.setWorkspaceDir("/tmp/workspace");
            config.setRequestBudget(5);

            service.save(config);

            String yaml = Files.readString(tempDir.resolve("config.yml"));
            assertTrue(yaml.contains("repositories:"));
            assertTrue(yaml.contains("id: demo"));
            assertTrue(yaml.contains("path: /tmp/demo"));
        } finally {
            System.setProperty("user.dir", originalDir.toString());
        }
    }
}
