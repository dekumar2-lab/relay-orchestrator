package com.relay.orchestrator.llm;

import com.relay.orchestrator.connection.ConnectionConfig;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic LLM client. Every agent in the pipeline calls this,
 * never Anthropic or Copilot SDK directly.
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request, ConnectionConfig config);

    ProviderId providerId();

    ProbeResult probe(ConnectionConfig config);

    default LlmResponse completeStreaming(LlmRequest request, ConnectionConfig config, TokenCallback onToken) {
        LlmResponse full = complete(request, config);
        if (full.text() != null && !full.text().isEmpty()) {
            onToken.onToken(full.text());
        }
        return full;
    }

    enum ProviderId {
        ANTHROPIC,
        COPILOT
    }

    record LlmRequest(
            String systemPrompt,
            List<Message> messages,
            String model,
            int maxTokens,
            double temperature,
            List<ToolDefinition> tools,
            ToolChoice toolChoice) {

        public static LlmRequest simple(String system, String user, String model, int maxTokens) {
            return new LlmRequest(
                    system,
                    List.of(new Message("user", user)),
                    model, maxTokens, 0.0,
                    List.of(), null);
        }

        public static LlmRequest probe(String model, int maxTokens) {
            return new LlmRequest(
                    null,
                    List.of(new Message("user", "Reply with the single word OK")),
                    model, maxTokens, 0.0,
                    List.of(), null);
        }

        public boolean hasTools() {
            return tools != null && !tools.isEmpty();
        }
    }

    record Message(
            String role,
            String content,
            String toolCallId,
            List<ToolCall> toolCalls) {

        public Message(String role, String content) {
            this(role, content, null, null);
        }

        public Message {
            if (!"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role)) {
                throw new IllegalArgumentException(
                        "role must be user, assistant, or tool, got: " + role);
            }
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message assistantWithToolCalls(List<ToolCall> calls) {
            return new Message("assistant", null, null, calls);
        }

        public static Message toolResult(String toolCallId, String content) {
            return new Message("tool", content, toolCallId, null);
        }
    }

    record ToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema) {
    }

    /**
     * Tells the model how it should (or must) use tools.
     * null on LlmRequest means "provider default" (usually AUTO).
     */
    record ToolChoice(Mode mode, String toolName) {

        public enum Mode {
            AUTO, // model decides whether to use a tool
            REQUIRED, // model must use some tool
            SPECIFIC // model must use the named tool
        }

        public static ToolChoice auto() {
            return new ToolChoice(Mode.AUTO, null);
        }

        public static ToolChoice required() {
            return new ToolChoice(Mode.REQUIRED, null);
        }

        public static ToolChoice specific(String toolName) {
            return new ToolChoice(Mode.SPECIFIC, toolName);
        }
    }

    record ToolCall(
            String id,
            String name,
            Map<String, Object> arguments) {
    }

    record LlmResponse(
            String text,
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheWriteTokens,
            String modelUsed,
            List<ToolCall> toolCalls,
            StopReason stopReason) {

        public static LlmResponse of(String text, int in, int out, String model) {
            return new LlmResponse(text, in, out, 0, 0, model, List.of(), StopReason.END_TURN);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        /** Total input the API processed = fresh + cache write + cache read. */
        public int totalInputTokens() {
            return inputTokens + cacheWriteTokens + cacheReadTokens;
        }
    }

    enum StopReason {
        END_TURN,
        TOOL_USE,
        MAX_TOKENS,
        STOP_SEQUENCE,
        UNKNOWN
    }

    record ProbeResult(boolean ok, String detail) {
        public static ProbeResult ok(String detail) {
            return new ProbeResult(true, detail);
        }

        public static ProbeResult fail(String detail) {
            return new ProbeResult(false, detail);
        }
    }

    @FunctionalInterface
    interface TokenCallback {
        void onToken(String delta);
    }
}