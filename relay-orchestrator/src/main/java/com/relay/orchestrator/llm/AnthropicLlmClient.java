package com.relay.orchestrator.llm;

import com.relay.orchestrator.connection.ConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    private final RestClient restClient = RestClient.create();

    @Override
    public ProviderId providerId() {
        return ProviderId.ANTHROPIC;
    }

    @Override
    public LlmResponse complete(LlmRequest request, ConnectionConfig config) {
        String apiKey = config.getAnthropicApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("Anthropic API key is empty");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new LlmException("Anthropic model is empty");
        }

        Map<String, Object> body = buildRequestBody(request);

        try {
            Map<?, ?> raw = restClient.post()
                    .uri(MESSAGES_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            return parseResponse(raw, request.model());
        } catch (RestClientException e) {
            throw new LlmException("Anthropic request failed: " + e.getMessage() + diagnose(e), e);
        }
    }

    @Override
    public ProbeResult probe(ConnectionConfig config) {
        try {
            LlmResponse r = complete(LlmRequest.probe(config.getModel(), 8), config);
            if (r.text() == null || r.text().isBlank()) {
                return ProbeResult.fail("model " + r.modelUsed() + " returned no text");
            }
            return ProbeResult.ok("model " + r.modelUsed() + " replied: " + r.text().trim());
        } catch (LlmException e) {
            return ProbeResult.fail(e.getMessage());
        } catch (Exception e) {
            return ProbeResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // Request construction
    // ----------------------------------------------------------------

    private Map<String, Object> buildRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            // Mark system prompt as cacheable. Anthropic caches for 5 minutes;
            // repeat reads bill at ~10% of normal input rate.
            // Requires >= 1024 tokens to actually cache; below that, no-op.
            body.put("system", List.of(Map.of(
                    "type", "text",
                    "text", request.systemPrompt(),
                    "cache_control", Map.of("type", "ephemeral"))));
        }

        body.put("messages", toAnthropicMessages(request.messages()));

        if (request.temperature() > 0.0) {
            body.put("temperature", request.temperature());
        }

        if (request.hasTools()) {
            body.put("tools", toAnthropicTools(request.tools()));
            if (request.toolChoice() != null) {
                body.put("tool_choice", toAnthropicToolChoice(request.toolChoice()));
            }
        }

        return body;
    }

    private List<Map<String, Object>> toAnthropicMessages(List<Message> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message m : messages) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            msg.put("content", List.of(Map.of("type", "text", "text", m.content())));
            out.add(msg);
        }
        return out;
    }

    private List<Map<String, Object>> toAnthropicTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            ToolDefinition t = tools.get(i);
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", t.name());
            tool.put("description", t.description());
            tool.put("input_schema", t.inputSchema());
            // Cache marker on the last tool — Anthropic caches everything
            // up to and including this block (system + all tools).
            if (i == tools.size() - 1) {
                tool.put("cache_control", Map.of("type", "ephemeral"));
            }
            out.add(tool);
        }
        return out;
    }

    private Map<String, Object> toAnthropicToolChoice(ToolChoice tc) {
        return switch (tc.mode()) {
            case AUTO     -> Map.of("type", "auto");
            case REQUIRED -> Map.of("type", "any");
            case SPECIFIC -> Map.of("type", "tool", "name", tc.toolName());
        };
    }

    // ----------------------------------------------------------------
    // Response parsing
    // ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private LlmResponse parseResponse(Map<?, ?> raw, String requestedModel) {
        if (raw == null) {
            throw new LlmException("Anthropic returned null body");
        }

        int inputTokens = 0;
        int outputTokens = 0;
        int cacheReadTokens = 0;
        int cacheWriteTokens = 0;
        if (raw.get("usage") instanceof Map<?, ?> usage) {
            inputTokens = intOrZero(usage.get("input_tokens"));
            outputTokens = intOrZero(usage.get("output_tokens"));
            cacheReadTokens = intOrZero(usage.get("cache_read_input_tokens"));
            cacheWriteTokens = intOrZero(usage.get("cache_creation_input_tokens"));
        }

        String modelUsed = raw.get("model") != null
                ? String.valueOf(raw.get("model"))
                : requestedModel;

        String stopReasonRaw = raw.get("stop_reason") != null
                ? String.valueOf(raw.get("stop_reason"))
                : "end_turn";
        StopReason stopReason = mapStopReason(stopReasonRaw);

        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        if (raw.get("content") instanceof List<?> contentList) {
            for (Object item : contentList) {
                if (!(item instanceof Map<?, ?> block)) continue;
                String type = block.get("type") != null ? String.valueOf(block.get("type")) : "";
                switch (type) {
                    case "text" -> {
                        Object t = block.get("text");
                        if (t != null) text.append(t);
                    }
                    case "tool_use" -> {
                        String id = block.get("id") != null ? String.valueOf(block.get("id")) : "";
                        String name = block.get("name") != null ? String.valueOf(block.get("name")) : "";
                        Map<String, Object> args = block.get("input") instanceof Map<?, ?> m
                                ? (Map<String, Object>) m
                                : Map.of();
                        toolCalls.add(new ToolCall(id, name, args));
                    }
                    default -> log.debug("Ignoring unknown Anthropic content block type: {}", type);
                }
            }
        }

        return new LlmResponse(
                text.length() == 0 ? null : text.toString(),
                inputTokens,
                outputTokens,
                cacheReadTokens,
                cacheWriteTokens,
                modelUsed,
                toolCalls,
                stopReason);
    }

    private StopReason mapStopReason(String raw) {
        return switch (raw) {
            case "end_turn" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "stop_sequence" -> StopReason.STOP_SEQUENCE;
            default -> StopReason.UNKNOWN;
        };
    }

    private int intOrZero(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private String diagnose(RestClientException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("401")) return " (401 Unauthorized - key invalid, revoked, or mistyped)";
        if (msg.contains("403")) return " (403 Forbidden - key lacks access or account suspended)";
        if (msg.contains("404")) return " (404 Not Found - model ID is wrong or retired)";
        if (msg.contains("429")) return " (429 Rate Limited)";
        if (msg.contains("500") || msg.contains("529")) return " (Anthropic server error - retry)";
        return "";
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}