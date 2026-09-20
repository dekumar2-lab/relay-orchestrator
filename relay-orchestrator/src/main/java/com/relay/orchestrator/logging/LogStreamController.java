package com.relay.orchestrator.logging;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class LogStreamController {

    private final LogBroadcaster broadcaster;

    public LogStreamController(LogBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /** The live log tail subscribes here via htmx's SSE extension. */
    @GetMapping(path = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }
}
