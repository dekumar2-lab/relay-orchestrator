package com.relay.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

@Service
public class RelayOrchestratorAgent {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            You are the Relay Race Orchestrator.

            ## CAVEMAN (compression)
            Respond terse. Strip articles, pleasantries, filler.
            Keep code, JSON, file paths, error messages, commands EXACT.

            ## PONYTAIL (YAGNI decision ladder)
            Before writing code:
            1) Does this feature need to exist? If not, say SKIP.
            2) Reuse existing code.
            3) Use Java stdlib.
            4) Use existing dependencies.
            5) Keep the diff to 1 line if possible.
            6) Only then, write the smallest implementation.

            Respect constraints from the story: skipTests, generateDesign, planOnly.
            """;

    public RelayOrchestratorAgent(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    public String execute(String context) {
        return chatClient.prompt()
                .user(context)
                .call()
                .content();
    }
}