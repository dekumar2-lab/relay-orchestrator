package com.relay.orchestrator.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Manages the local embedding model files.
 *
 * Files live in ./models/all-MiniLM-L6-v2/ relative to the project root
 * (i.e., the directory where the app is started). This keeps the entire
 * framework self-contained: the project directory is the complete unit.
 *
 * Behavior on startup:
 * 1. If model files are already present, use them. No network.
 * 2. If not present, download from HuggingFace once.
 * 3. If neither works, embedding service degrades gracefully.
 *
 * To pre-load the model for offline use: copy the four files into
 * ./models/all-MiniLM-L6-v2/ and the download step is skipped.
 *
 * Optional override: set RELAY_MODELS_DIR env var to relocate the folder
 * (e.g., a shared network drive or a mounted volume).
 */
@Service
public class ModelProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ModelProvisioner.class);

    private static final String HF_BASE = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/";

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private final Path modelDir;

    public ModelProvisioner() {
        String override = System.getenv("RELAY_MODELS_DIR");
        if (override != null && !override.isBlank()) {
            this.modelDir = Paths.get(override).resolve("all-MiniLM-L6-v2");
        } else {
            this.modelDir = Paths.get("models").resolve("all-MiniLM-L6-v2").toAbsolutePath();
        }
        log.info("Model directory: {}", modelDir);
    }

    /** Ensure all model files exist. Returns true if ready to use. */
    public synchronized boolean ensureModelFiles() {
        try {
            Files.createDirectories(modelDir);
        } catch (IOException e) {
            log.error("Failed to create model directory: {}", modelDir, e);
            return false;
        }

        boolean ok = ensureFile("model.onnx", "onnx/model.onnx", 10_000_000);
        ok = ok && ensureFile("tokenizer.json", "tokenizer.json", 100_000);
        ok = ok && ensureFile("vocab.txt", "vocab.txt", 10_000);
        ok = ok && ensureFile("config.json", "config.json", 100);
        return ok;
    }

    public Path path(String filename) {
        return modelDir.resolve(filename);
    }

    public Path modelDir() {
        return modelDir;
    }

    private boolean ensureFile(String localName, String remotePath, long minBytes) {
        Path target = modelDir.resolve(localName);

        if (Files.exists(target)) {
            try {
                long size = Files.size(target);
                if (size >= minBytes) {
                    log.debug("Model file present: {} ({} bytes)", localName, size);
                    return true;
                }
                log.warn("Model file {} looks truncated ({} bytes < {}); re-downloading",
                        localName, size, minBytes);
            } catch (IOException e) {
                log.warn("Failed to stat {}, will re-download", target, e);
            }
        }

        String url = HF_BASE + remotePath;
        log.info("Downloading {} (min {} bytes) from HuggingFace...", localName, minBytes);
        try {
            downloadFile(url, target);
            long size = Files.size(target);
            if (size < minBytes) {
                log.error("Downloaded {} but only {} bytes (expected ≥{})",
                        localName, size, minBytes);
                return false;
            }
            log.info("Saved {} ({} bytes)", localName, size);
            return true;
        } catch (Exception e) {
            log.error("Failed to download {} from {}: {}", localName, url, e.getMessage());
            log.error("To work offline: place the file at {}", target);
            return false;
        }
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        }

        long expected = resp.headers().firstValueAsLong("content-length").orElse(-1L);

        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }

        long actual = Files.size(tmp);
        if (expected > 0 && actual != expected) {
            Files.deleteIfExists(tmp);
            throw new IOException("Size mismatch: expected " + expected + ", got " + actual);
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}