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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class LogBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LogBroadcaster.class);
    private static final int REPLAY_BUFFER_SIZE = 200;
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // Create a dedicated lock object specifically to protect our replay buffer queue
    private final Object bufferLock = new Object();
    private final Deque<LogEvent> replayBuffer = new ArrayDeque<>();

    // CHANGED: dedicated single-thread executor for cleanup.
    // Calling emitter.complete() on the publishing thread can fight the Tomcat
    // async flush machinery and cascade into "connection aborted" IOExceptions
    // logged by the dispatcher servlet. Moving completion off the publishing
    // thread avoids that re-entrancy.
    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-cleanup");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        // CHANGED: each callback routes through the async cleanup so we never
        // call complete() inline from a container callback.
        emitter.onCompletion(() -> scheduleCleanup(emitter));
        emitter.onTimeout(() -> scheduleCleanup(emitter));
        emitter.onError(ex -> {
            if (isSocketDisconnect(ex)) {
                log.debug("SSE client errored (disconnect); scheduling cleanup");
            } else {
                log.warn("SSE client errored", ex);
            }
            scheduleCleanup(emitter);
        });

        emitters.add(emitter);

        List<LogEvent> history;
        synchronized (bufferLock) {
            history = new ArrayList<>(replayBuffer);
        }
        for (LogEvent buffered : history) {
            // CHANGED: if replay fails, stop replaying to this emitter immediately.
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
                // CHANGED: never rethrow; the container's async flush can still
                // surface this later as a dispatcherServlet ERROR. See
                // TomcatNoiseSuppressorConfig for the container-level silencer.
                if (isSocketDisconnect(t)) {
                    log.debug("SSE client disconnected; dropping dead subscriber");
                } else {
                    log.warn("SSE publish failed for live subscriber", t);
                }
                scheduleCleanup(emitter);
            }
        }
    }

    /**
     * @return true if the event was delivered (or accepted into the container's
     *         buffer), false if the emitter should be abandoned.
     */
    private boolean sendTo(SseEmitter emitter, LogEvent event) {
        try {
            emitter.send(SseEmitter.event().name("logline").data(event.toHtmlRow()));
            return true;
        } catch (Throwable t) {
            if (isSocketDisconnect(t)) {
                log.debug("SSE client disconnected while replaying history");
            } else {
                log.warn("SSE replay failed", t);
            }
            scheduleCleanup(emitter);
            return false;
        }
    }

    /**
     * CHANGED: scheduled rather than inline. Guarded with an AtomicBoolean per
     * emitter so that onCompletion + onError firing in quick succession don't
     * both enqueue a cleanup for the same emitter.
     */
    private void scheduleCleanup(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        // CopyOnWriteArrayList.remove is idempotent, but we still want to avoid
        // queueing duplicate tasks. A simple check then submit is fine here.
        if (!emitters.contains(emitter)) {
            return;
        }
        cleanupExecutor.execute(() -> cleanupDeadEmitter(emitter));
    }

    private void cleanupDeadEmitter(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        // CHANGED: remove first so nothing new tries to publish to it while
        // we're completing it.
        emitters.remove(emitter);
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // client already dropped; nothing else to do
        }
    }

    private boolean isSocketDisconnect(Throwable t) {
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
            if (className != null && className.toLowerCase(Locale.ROOT).contains("clientabortexception")) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}