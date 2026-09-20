package com.relay.orchestrator.connection;

/**
 * Local single-user settings persisted in ~/.relay-orchestrator/config.yml.
 */
public class ConnectionConfig {

    public enum Provider {
        GITHUB_COPILOT("GitHub Copilot"),
        CLAUDE_API("Claude API (direct)");

        private final String label;

        Provider(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private Provider provider = Provider.GITHUB_COPILOT;
    private String githubToken = "";
    private String anthropicApiKey = "";
    private String model = "claude-sonnet-4-5";
    private String workspaceDir = "";
    private int requestBudget = 20;

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider == null ? Provider.GITHUB_COPILOT : provider;
    }

    public String getGithubToken() {
        return githubToken;
    }

    public void setGithubToken(String githubToken) {
        this.githubToken = githubToken == null ? "" : githubToken;
    }

    public String getAnthropicApiKey() {
        return anthropicApiKey;
    }

    public void setAnthropicApiKey(String anthropicApiKey) {
        this.anthropicApiKey = anthropicApiKey == null ? "" : anthropicApiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null || model.isBlank() ? "claude-sonnet-4-5" : model;
    }

    public String getWorkspaceDir() {
        return workspaceDir;
    }

    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir == null ? "" : workspaceDir;
    }

    public int getRequestBudget() {
        return requestBudget;
    }

    public void setRequestBudget(int requestBudget) {
        this.requestBudget = requestBudget;
    }

    public String getProviderSummary() {
        return provider.getLabel() + " · " + model;
    }
}
