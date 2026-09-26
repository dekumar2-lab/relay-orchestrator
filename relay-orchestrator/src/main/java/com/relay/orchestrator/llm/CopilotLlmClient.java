package com.relay.orchestrator.llm;

import com.relay.orchestrator.connection.ConnectionConfig;
import com.relay.orchestrator.llm.copilot.CopilotNodeBridge;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CopilotLlmClient implements LlmClient {

    private final CopilotNodeBridge bridge;

    public CopilotLlmClient(CopilotNodeBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.COPILOT;
    }

    @Override
    @SuppressWarnings("unchecked")
    public LlmResponse complete(LlmRequest request, ConnectionConfig config) {
        if (request.model() == null || request.model().isBlank()) {
            throw new LlmException("Copilot model is empty");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "chat");
        body.put("model", request.model());
        body.put("maxTokens", request.maxTokens());
        if (request.temperature() > 0.0)
            body.put("temperature", request.temperature());

        // Pass the token from config so the bridge uses it directly.
        // Empty falls back to env / VS Code / gh CLI inside the bridge.
        String token = config.getGithubToken();
        if (token != null && !token.isBlank()) {
            body.put("githubToken", token);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        for (Message m : request.messages()) {
            messages.add(serializeMessage(m));
        }
        body.put("messages", messages);

        if (request.hasTools()) {
            body.put("tools", toOpenAiTools(request.tools()));
            if (request.toolChoice() != null) {
                body.put("toolChoice", toOpenAiToolChoice(request.toolChoice()));
            }
        }

        Map<String, Object> response;
        try {
            response = bridge.call(body);
        } catch (CopilotNodeBridge.BridgeUnavailable e) {
            throw new LlmException("Copilot bridge unavailable: " + e.getMessage(), e);
        }

        if (!Boolean.TRUE.equals(response.get("ok"))) {
            String err = response.get("error") != null ? String.valueOf(response.get("error")) : "unknown";
            throw new LlmException("Copilot error: " + err);
        }

        return toLlmResponse(response, request.model());
    }

    @Override
    public ProbeResult probe(ConnectionConfig config) {
        try {
            LlmResponse r = complete(LlmRequest.probe(config.getModel(), 16), config);
            if (r.text() == null || r.text().isBlank()) {
                return ProbeResult.fail("Copilot returned no text");
            }
            return ProbeResult.ok("Copilot replied: " + r.text().trim());
        } catch (Exception e) {
            return ProbeResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, Object> serializeMessage(Message m) {
        if ("tool".equals(m.role())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", "tool");
            out.put("tool_call_id", m.toolCallId());
            out.put("content", m.content() == null ? "" : m.content());
            return out;
        }
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("role", "assistant");
            out.put("content", m.content());
            out.put("tool_calls", serializeToolCallsForRequest(m.toolCalls()));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", m.role());
        out.put("content", m.content() == null ? "" : m.content());
        return out;
    }

    private List<Map<String, Object>> serializeToolCallsForRequest(List<ToolCall> calls) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolCall tc : calls) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", tc.id());
            call.put("type", "function");
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tc.name());
            fn.put("arguments", toJson(tc.arguments()));
            call.put("function", fn);
            out.add(call);
        }
        return out;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private List<Map<String, Object>> toOpenAiTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDefinition t : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.put("parameters", t.inputSchema());
            out.add(Map.of("type", "function", "function", fn));
        }
        return out;
    }

    private Object toOpenAiToolChoice(ToolChoice tc) {
        return switch (tc.mode()) {
            case AUTO -> "auto";
            case REQUIRED -> "required";
            case SPECIFIC -> Map.of("type", "function", "function", Map.of("name", tc.toolName()));
        };
    }

    @SuppressWarnings("unchecked")
    private LlmResponse toLlmResponse(Map<String, Object> raw, String requestedModel) {
        String text = raw.get("text") != null ? String.valueOf(raw.get("text")) : null;

        int in = 0, out = 0, cacheRead = 0;
        if (raw.get("usage") instanceof Map<?, ?> usage) {
            in = intOrZero(usage.get("prompt_tokens"));
            out = intOrZero(usage.get("completion_tokens"));
            cacheRead = intOrZero(usage.get("cached_tokens"));
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        if (raw.get("toolCalls") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> tc))
                    continue;
                String id = tc.get("id") != null ? String.valueOf(tc.get("id")) : "";
                String name = tc.get("name") != null ? String.valueOf(tc.get("name")) : "";
                Map<String, Object> args = tc.get("arguments") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : Map.of();
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        String modelUsed = raw.get("model") != null ? String.valueOf(raw.get("model")) : requestedModel;
        String finish = raw.get("finishReason") != null ? String.valueOf(raw.get("finishReason")) : null;

        StopReason stop = !toolCalls.isEmpty() ? StopReason.TOOL_USE
                : ("stop".equals(finish) ? StopReason.END_TURN
                        : ("tool_calls".equals(finish) ? StopReason.TOOL_USE
                                : ("length".equals(finish) ? StopReason.MAX_TOKENS : StopReason.UNKNOWN)));

        return new LlmResponse(text, in, out, cacheRead, 0, modelUsed, toolCalls, stop);
    }

    private int intOrZero(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String m) {
            super(m);
        }

        public LlmException(String m, Throwable c) {
            super(m, c);
        }
    }
}