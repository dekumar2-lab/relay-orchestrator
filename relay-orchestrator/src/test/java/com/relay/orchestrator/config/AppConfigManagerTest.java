package com.relay.orchestrator.config;

import com.relay.orchestrator.connection.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigManagerTest {

    private static final ApplicationEventPublisher NOOP = e -> {};

    @TempDir Path tempDir;

    private AppConfigManager manager() {
        return new AppConfigManager(tempDir.resolve("config.yml"), NOOP);
    }

    @Test
    void updateFromNullIsNoOp() {
        AppConfigManager m = manager();
        m.setGithubToken("github_pat_abc");
        m.updateFrom(null);
        assertThat(m.getGithubToken()).isEqualTo("github_pat_abc");
    }

    @Test
    void updateFromBlankTokenPreservesStoredToken() {
        AppConfigManager m = manager();
        m.setGithubToken("github_pat_abc");

        ConnectionConfig partial = new ConnectionConfig();
        partial.setGithubToken("");
        partial.setModel("gpt-4o");
        m.updateFrom(partial);

        assertThat(m.getGithubToken()).isEqualTo("github_pat_abc");
    }

    @Test
    void updateFromOnlyModelPreservesEverythingElse() {
        AppConfigManager m = manager();
        m.setGithubToken("github_pat_abc");
        m.setWorkspaceDir("/tmp/ws");

        ConnectionConfig partial = new ConnectionConfig();
        partial.setModel("gpt-4o-mini");
        m.updateFrom(partial);

        assertThat(m.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(m.getGithubToken()).isEqualTo("github_pat_abc");
        assertThat(m.getWorkspaceDir()).isEqualTo("/tmp/ws");
    }

    @Test
    void loadTreatsYamlNullsAsDefaults() throws Exception {
        Path cfg = tempDir.resolve("config.yml");
        Files.writeString(cfg, """
                provider: GITHUB_COPILOT
                githubToken:
                model: gpt-4o
                requestBudget:
                repositories: []
                """);

        AppConfigManager m = new AppConfigManager(cfg, NOOP);
        m.loadSettingsFromDisk();

        assertThat(m.getGithubToken()).isEmpty();
        assertThat(m.getRequestBudget()).isEqualTo(20);
        assertThat(m.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void updateFromPersistsToDisk() {
        AppConfigManager m = manager();
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setGithubToken("github_pat_xyz");
        m.updateFrom(cfg);

        assertThat(tempDir.resolve("config.yml")).exists();

        AppConfigManager reloaded = new AppConfigManager(
                tempDir.resolve("config.yml"), NOOP);
        reloaded.loadSettingsFromDisk();
        assertThat(reloaded.getGithubToken()).isEqualTo("github_pat_xyz");
    }
}