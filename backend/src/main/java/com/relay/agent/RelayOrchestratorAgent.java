package com.relay.agent;

import com.relay.model.SseEvent;
import com.relay.service.SseEmitterService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RelayOrchestratorAgent {

    private final ChatClient chatClient;
    private final SseEmitterService sse;

    private static final String SYSTEM_PROMPT = """
            You are the Relay Race Orchestrator. Respond terse (Caveman mode).
            Strip articles, pleasantries, filler. Keep code, paths, errors EXACT.
            """;

    public RelayOrchestratorAgent(ChatModel chatModel, SseEmitterService sse) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.sse = sse;
    }

    public LlmResult execute(String pipelineId, String context) {
        long start = System.currentTimeMillis();

        sse.emit(pipelineId, new SseEvent("llm_trace", "prompt",
                java.util.Map.of("userPrompt", context, "timestamp", start)));

        ChatResponse response = chatClient.prompt()
                .user(context)
                .call()
                .chatResponse();

        String content = response.getResult().getOutput().getContent();
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
        long input = usage != null ? usage.getPromptTokens() : 0;
        long output = usage != null ? usage.getGenerationTokens() : 0;
        long elapsed = System.currentTimeMillis() - start;

        sse.emit(pipelineId, new SseEvent("llm_trace", "response",
                java.util.Map.of(
                        "content", content != null ? content : "",
                        "inputTokens", input,
                        "outputTokens", output,
                        "elapsedMs", elapsed,
                        "timestamp", System.currentTimeMillis())));

        return new LlmResult(content, input, output, elapsed);
    }

    public record LlmResult(String content, long inputTokens, long outputTokens, long elapsedMs) {
    }

}