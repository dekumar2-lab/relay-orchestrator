package com.relay.orchestrator.llm;

import com.relay.orchestrator.connection.ConnectionConfig;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic LLM client. Every agent in the pipeline calls this,
 * never Anthropic or Copilot SDK directly.
 *
 * Implementations:
 *   - AnthropicLlmClient  (real, wired)
 *   - CopilotLlmClient    (stub until subscription is available)
 *
 * The whole point of this interface is that Phase 2 agents can be written
 * once, and swapping provider is a one-line config change.
 */
public interface LlmClient {

    /**
     * Single-shot prompt. Blocks until the model replies or times out.
     */
    LlmResponse complete(LlmRequest request, ConnectionConfig config);

    /** Which provider this client represents. Used for logging + UI. */
    ProviderId providerId();

    /**
     * Quick health probe used by the Settings page. Should be lightweight
     * (small max_tokens) and return a short human-readable detail string.
     */
    ProbeResult probe(ConnectionConfig config);

    // ----------------------------------------------------------------
    // Provider identity
    // ----------------------------------------------------------------

    enum ProviderId {
        ANTHROPIC,
        COPILOT
    }

    // ----------------------------------------------------------------
    // Request / response records
    // ----------------------------------------------------------------

    /**
     * A request to the LLM. Immutable. Use LlmRequest.simple() for the
     * common "system + one user message" case.
     */
    record LlmRequest(
            String systemPrompt,
            List<Message> messages,
            String model,
            int maxTokens,
            double temperature,
            List<ToolDefinition> tools) {

        public static LlmRequest simple(String system, String user, String model, int maxTokens) {
            return new LlmRequest(
                    system,
                    List.of(new Message("user", user)),
                    model,
                    maxTokens,
                    0.0,
                    List.of());
        }

        /** System-only request (e.g. probe). */
        public static LlmRequest probe(String model, int maxTokens) {
            return new LlmRequest(
                    null,
                    List.of(new Message("user", "Reply with the single word OK")),
                    model,
                    maxTokens,
                    0.0,
                    List.of());
        }

        public boolean hasTools() {
            return tools != null && !tools.isEmpty();
        }
    }

    record Message(String role, String content) {
        public Message {
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalArgumentException("role must be user or assistant, got: " + role);
            }
        }
    }

    /**
     * Tool (function) definition for the LLM. Input schema is JSON Schema.
     */
    record ToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema) {
    }

    /**
     * A single tool call requested by the model.
     */
    record ToolCall(
            String id,
            String name,
            Map<String, Object> arguments) {
    }

    /**
     * The model's reply. toolCalls is empty if the model just produced text.
     * If toolCalls is non-empty, text may be null or a preamble.
     */
    record LlmResponse(
            String text,
            int inputTokens,
            int outputTokens,
            String modelUsed,
            List<ToolCall> toolCalls,
            StopReason stopReason) {

        public static LlmResponse of(String text, int in, int out, String model) {
            return new LlmResponse(text, in, out, model, List.of(), StopReason.END_TURN);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    enum StopReason {
        END_TURN,       // model finished naturally
        TOOL_USE,       // model wants to call a tool
        MAX_TOKENS,     // hit the token cap
        STOP_SEQUENCE,  // hit a stop sequence
        UNKNOWN
    }

    record ProbeResult(boolean ok, String detail) {
        public static ProbeResult ok(String detail)  { return new ProbeResult(true, detail); }
        public static ProbeResult fail(String detail) { return new ProbeResult(false, detail); }
    }

    @FunctionalInterface
    interface TokenCallback {
        void onToken(String delta);
    }

     /** Streaming variant. Default buffers then emits one delta. */
    default LlmResponse completeStreaming(LlmRequest request, ConnectionConfig config, TokenCallback onToken) {
        LlmResponse full = complete(request, config);
        if (full.text() != null && !full.text().isEmpty()) {
            onToken.onToken(full.text());
        }
        return full;
    }
}