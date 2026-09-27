package com.relay.orchestrator.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class LogBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LogBroadcaster.class);
    private static final int REPLAY_BUFFER_SIZE = 200;
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final Object bufferLock = new Object();
    private final Deque<LogEvent> replayBuffer = new ArrayDeque<>();

    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-cleanup");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        emitter.onCompletion(() -> scheduleCleanup(emitter));
        emitter.onTimeout(() -> scheduleCleanup(emitter));
        emitter.onError(ex -> {
            if (isSocketDisconnect(ex)) {
                log.debug("SSE client errored (disconnect); scheduling cleanup");
            } else {
                log.warn("SSE client errored: {}", ex.getMessage());
            }
            scheduleCleanup(emitter);
        });

        emitters.add(emitter);

        List<LogEvent> history;
        synchronized (bufferLock) {
            history = new ArrayList<>(replayBuffer);
        }
        for (LogEvent buffered : history) {
            if (!sendTo(emitter, buffered)) {
                break;
            }
        }
        return emitter;
    }

    public void publish(LogEvent event) {
        synchronized (bufferLock) {
            replayBuffer.addLast(event);
            while (replayBuffer.size() > REPLAY_BUFFER_SIZE) {
                replayBuffer.removeFirst();
            }
        }

        for (SseEmitter emitter : new ArrayList<>(emitters)) {
            try {
                emitter.send(SseEmitter.event().name("logline").data(event.toHtmlRow()));
            } catch (Throwable t) {
                if (isSocketDisconnect(t)) {
                    log.debug("SSE client disconnected; dropping dead subscriber");
                } else {
                    log.warn("SSE publish failed for live subscriber: {}", t.getMessage());
                }
                scheduleCleanup(emitter);
            }
        }
    }

    private boolean sendTo(SseEmitter emitter, LogEvent event) {
        try {
            emitter.send(SseEmitter.event().name("logline").data(event.toHtmlRow()));
            return true;
        } catch (Throwable t) {
            if (isSocketDisconnect(t)) {
                log.debug("SSE client disconnected while replaying history");
            } else {
                log.warn("SSE replay failed: {}", t.getMessage());
            }
            scheduleCleanup(emitter);
            return false;
        }
    }

    private void scheduleCleanup(SseEmitter emitter) {
        if (emitter == null)
            return;
        if (!emitters.contains(emitter))
            return;
        cleanupExecutor.execute(() -> cleanupDeadEmitter(emitter));
    }

    private void cleanupDeadEmitter(SseEmitter emitter) {
        if (emitter == null)
            return;
        emitters.remove(emitter);
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // client already dropped
        }
    }

    private boolean isSocketDisconnect(Throwable t) {
        // Guard against emitters that the container has already completed.
        // The next send() throws IllegalStateException with a specific message.
        if (t instanceof IllegalStateException) {
            String m = t.getMessage();
            if (m != null && m.contains("ResponseBodyEmitter has already completed")) {
                return true;
            }
        }

        Set<String> disconnectMarkers = new HashSet<>(Arrays.asList(
                "broken pipe",
                "connection aborted",
                "connection reset",
                "connection reset by peer",
                "socket closed",
                "disconnected",
                "aborted by the software in your host machine",
                "software caused connection abort",
                "connection closed",
                "connection has been closed",
                "an existing connection was forcibly closed",
                "responsebodyemitter has already completed",
                "ioexception: broken pipe",
                "ioexception: connection reset by peer",
                "java.net.socketexception: broken pipe",
                "java.net.socketexception: connection reset",
                "java.net.socketexception: socket closed",
                "java.io.ioexception: broken pipe",
                "org.apache.catalina.connector.clientabortexception",
                "clientabortexception"));

        Throwable current = t;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lowered = message.toLowerCase(Locale.ROOT);
                for (String marker : disconnectMarkers) {
                    if (lowered.contains(marker)) {
                        return true;
                    }
                }
            }

            String className = current.getClass().getName();
            if (className != null
                    && className.toLowerCase(Locale.ROOT).contains("clientabortexception")) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}