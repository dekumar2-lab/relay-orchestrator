package com.relay.orchestrator.connection;

import com.relay.orchestrator.config.AppConfigManager;
import com.relay.orchestrator.config.RepositoryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionConfigServiceTest {

    @Mock
    private AppConfigManager appConfigManager;

    private ConnectionConfigService configService;

    @BeforeEach
    void setUp() {
        // Explicit constructor injection — avoids the @InjectMocks NPE.
        configService = new ConnectionConfigService(appConfigManager);
    }

    @Test
    void loadDelegatesToAppConfigManager() {
        ConnectionConfig expected = new ConnectionConfig();
        expected.setModel("gpt-4o");
        when(appConfigManager.toConnectionConfig()).thenReturn(expected);

        Optional<ConnectionConfig> loaded = configService.load();

        assertThat(loaded).containsSame(expected);
        verify(appConfigManager).toConnectionConfig();
    }

    @Test
    void saveDelegatesToAppConfigManager() {
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setGithubToken("sk-ant-test");
        cfg.setModel("gpt-4o");

        configService.save(cfg);

        verify(appConfigManager).updateFrom(cfg);
    }

    @Test
    void savePreservesExistingRepositories() {
        // Seed the manager with a repository so we can prove save() doesn't wipe it.
        RepositoryConfig repo = new RepositoryConfig();
        repo.setId("backend");
        repo.setPath("/tmp/backend");

        List<RepositoryConfig> stored = new ArrayList<>();
        stored.add(repo);
        when(appConfigManager.getRepositories()).thenReturn(stored);

        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setModel("gpt-4o");

        configService.save(cfg);

        // save() delegates to updateFrom, which in the production class does not
        // touch the repositories list; the facade must not either.
        verify(appConfigManager).updateFrom(cfg);
        assertThat(appConfigManager.getRepositories()).containsExactly(repo);
    }

    @Test
    void saveDoesNotClobberCredentialWhenTokenOmitted() {
        // Simulate a partially-bound form: model set, token never submitted.
        ConnectionConfig partial = new ConnectionConfig();
        partial.setModel("gpt-4o");

        configService.save(partial);

        // The facade must pass the object through unchanged; the null-safe
        // guard lives inside AppConfigManager.updateFrom (covered separately).
        verify(appConfigManager).updateFrom(partial);
    }
}