package com.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.model.PipelineContext;
import com.relay.model.SseEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SseEmitterService {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public SseEmitter register(String pipelineId, PipelineContext ctx) {
        SseEmitter emitter = new SseEmitter(300_000L);
        emitter.onCompletion(() -> emitters.remove(pipelineId));
        emitter.onTimeout(() -> emitters.remove(pipelineId));
        emitters.put(pipelineId, emitter);

        // Replay the last 10 log lines
        List<String> history = ctx.getLogHistory();
        int start = Math.max(0, history.size() - 10);
        for (int i = start; i < history.size(); i++) {
            try {
                SseEvent evt = new SseEvent("log", history.get(i), null);
                emitter.send(SseEmitter.event().name("log").data(mapper.writeValueAsString(evt)));
            } catch (IOException ignored) {
            }
        }
        return emitter;
    }

    public void emit(String pipelineId, SseEvent event) {
        SseEmitter emitter = emitters.get(pipelineId);
        if (emitter == null) return;
        try {
            // Serialize the SseEvent as JSON so the frontend can JSON.parse it
            String json = mapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().name(event.getType()).data(json));
        } catch (IOException e) {
            emitters.remove(pipelineId);
        }
    }

    public void complete(String pipelineId) {
        SseEmitter emitter = emitters.get(pipelineId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        emitters.remove(pipelineId);
    }
}