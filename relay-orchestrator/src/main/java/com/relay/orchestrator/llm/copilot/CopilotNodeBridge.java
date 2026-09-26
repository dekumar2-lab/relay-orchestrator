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
import java.util.concurrent.TimeUnit;
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

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private volatile boolean ready = false;

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
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
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

            String responseLine = stdout.readLine();
            if (responseLine == null) {
                ready = false;
                throw new BridgeUnavailable("Bridge closed stdout");
            }
            return mapper.readValue(responseLine, Map.class);
        } catch (IOException e) {
            throw new BridgeUnavailable("Bridge I/O failed: " + e.getMessage(), e);
        } finally {
            callLock.unlock();
        }
    }

    public static class BridgeUnavailable extends RuntimeException {
        public BridgeUnavailable(String m) { super(m); }
        public BridgeUnavailable(String m, Throwable c) { super(m, c); }
    }
}