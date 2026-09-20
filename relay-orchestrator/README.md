# Relay Orchestrator

## What this build does

This version keeps the app local and single-user, but it adds the navigation shell and the provider-aware connection flow:

- persistent header bar and left sidebar layout
- Settings page with the connection form moved there
- GitHub Copilot vs Claude API provider switching
- honest placeholders for pipeline, topology, token usage, code preview, and support pages
- live log SSE view reused through the terminal logs page
- last connection status persisted in memory for the header status pill

## Run it

```bash
cd relay-orchestrator
mvn spring-boot:run
```

Open http://localhost:8081

## Settings location

The connection form now lives at the Settings screen, not the old single-page connection screen.

- route: `/settings`
- config file: `~/.relay-orchestrator/config.yml`
- fields: provider, GitHub token, Anthropic API key, model, workspace directory, request budget

## Provider switch

The Settings page exposes two provider choices:

- GitHub Copilot
- Claude API (direct)

The selected provider is saved in the same config file along with the existing connection data.

### GitHub Copilot path

- uses the existing GitHub token flow
- keeps the Copilot validation path unchanged from the old app
- expects the GitHub token to have the Copilot Requests permission
- requires local Copilot CLI access for the real validation round trip

### Claude API path

- shows an Anthropic API key field instead of the GitHub token field
- help text points to `console.anthropic.com/settings/keys`
- model help text points to model names like `claude-sonnet-4-5` or `claude-opus-4-5`
- validates by calling the Anthropic Messages API at `https://api.anthropic.com/v1/messages`

## Live logs

The existing SSE log broadcaster is still the source of truth. It is reused on the Terminal Logs screen and the Settings screen.

## Test steps

### GitHub Copilot

1. Open `/settings`.
2. Select `GitHub Copilot`.
3. Enter a valid GitHub token and workspace directory.
4. Click `Test Connection`.
5. Watch the live log for the validation sequence, including the token check and Copilot reply.

### Claude API

1. Open `/settings`.
2. Select `Claude API (direct)`.
3. Enter the Anthropic API key and a Claude model name such as `claude-sonnet-4-5`.
4. Click `Test Connection`.
5. Watch the log for the Anthropic validation flow: validating key, sending test prompt, model reply, connection validated.

# llm package

The provider seam for every AI call in the orchestrator.

## Rules

1. **No class outside this package may import Anthropic or Copilot SDK types.**
   If you find yourself writing `import org.springframework.web.client.RestClient`
   in an agent, you're doing it wrong — call `LlmClientRouter` instead.

2. **The API key never lives inside an `LlmRequest`.** It lives in `ConnectionConfig`
   and is passed to `AnthropicLlmClient.completeWithKey()` by the router.

3. **Every response is a `LlmResponse` record.** No raw `Map<?,?>` escapes this
   package. If Anthropic adds a field you need, add it to `LlmResponse`.

## Adding a provider

1. Implement `LlmClient`.
2. Annotate with `@Service` so it's picked up by `LlmClientRouter`.
3. Done — `LlmClientRouter` auto-registers it.

## Adding tool-use

`LlmRequest.tools` and `LlmResponse.toolCalls` are already wired. Add tools
via `LlmRequest.builder()` patterns in Phase 2. The Anthropic client's
`parseResponse` already handles `tool_use` content blocks.

## Honest placeholders

This build does not invent pipeline execution, repository indexing, or code generation data. The following pages intentionally remain empty-state placeholders:

- Pipeline Stages
- Workspace Topology
- Code Preview
- Token Tracker usage totals
- Support page

## Important limitation

This remains a local single-user app. It does not implement OAuth, deployment-specific multi-user auth, real pipeline runs, or repo indexing workflows.
