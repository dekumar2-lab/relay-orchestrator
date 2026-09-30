package com.relay.orchestrator.llm.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the Node.js Copilot bridge as a persistent subprocess.
 * See bridge/copilot-bridge.js for the protocol details.
 */
@Service
public class CopilotNodeBridge {

    private static final Logger log = LoggerFactory.getLogger(CopilotNodeBridge.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock callLock = new ReentrantLock();
    private static final long CALL_TIMEOUT_MS = 90_000;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private volatile boolean ready = false;

    /**
     * Single-threaded executor that runs the blocking stdout.readLine().
     * Kept separate from callLock so a hung read does not deadlock the
     * calling thread — the Future.get() below enforces the timeout.
     */
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bridge-reader");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void start() {
        try {
            File bridge = new File(System.getProperty("user.dir"), "bridge/copilot-bridge.js");
            if (!bridge.exists()) {
                log.error("Bridge script not found at {}", bridge.getAbsolutePath());
                return;
            }
            log.info("Starting Copilot bridge: {}", bridge.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder("node", bridge.getAbsolutePath());
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.directory(new File(System.getProperty("user.dir")));

            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            ready = true;
            log.info("Copilot bridge started (PID {})", process.pid());
        } catch (IOException e) {
            ready = false;
            log.error("Failed to start bridge: {}", e.getMessage());
            log.error("Ensure Node.js is on PATH and bridge/copilot-bridge.js exists.");
        }
    }

    @PreDestroy
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS))
                    process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        readExecutor.shutdownNow();
    }

    public boolean isReady() {
        return ready && process != null && process.isAlive();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> call(Map<String, Object> request) {
        if (!isReady()) {
            throw new BridgeUnavailable("Copilot bridge is not running");
        }

        callLock.lock();
        try {
            request.put("id", UUID.randomUUID().toString());
            stdin.write(mapper.writeValueAsString(request));
            stdin.newLine();
            stdin.flush();

            // Read on a separate thread so we can time-bound the wait.
            // A hung Copilot API call previously deadlocked the whole LLM
            // path because readLine() had no timeout.
            Future<String> readFuture = readExecutor.submit(() -> stdout.readLine());

            String responseLine;
            try {
                responseLine = readFuture.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                readFuture.cancel(true);
                ready = false;
                log.error("Bridge call timed out after {}ms; marking bridge dead",
                        CALL_TIMEOUT_MS);
                throw new BridgeUnavailable(
                        "Copilot bridge call timed out after " + CALL_TIMEOUT_MS + "ms");
            } catch (InterruptedException e) {
                readFuture.cancel(true);
                ready = false;
                Thread.currentThread().interrupt();
                throw new BridgeUnavailable("Interrupted waiting for bridge", e);
            } catch (ExecutionException e) {
                ready = false;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new BridgeUnavailable("Bridge read failed: " + cause.getMessage(), cause);
            }

            if (responseLine == null) {
                ready = false;
                throw new BridgeUnavailable("Bridge closed stdout");
            }
            return mapper.readValue(responseLine, Map.class);
        } catch (IOException e) {
            ready = false;
            throw new BridgeUnavailable("Bridge I/O failed: " + e.getMessage(), e);
        } finally {
            callLock.unlock();
        }
    }

    public static class BridgeUnavailable extends RuntimeException {
        public BridgeUnavailable(String m) {
            super(m);
        }

        public BridgeUnavailable(String m, Throwable c) {
            super(m, c);
        }
    }
}