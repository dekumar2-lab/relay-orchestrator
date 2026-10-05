package com.relay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
@CrossOrigin(origins = "http://localhost:4200")
public class WorkspaceController {

    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/summary")
    public ResponseEntity<?> summary() {
        try (InputStream in = new ClassPathResource("mock-repos/graphify-report.json").getInputStream()) {
            return ResponseEntity.ok(mapper.readTree(in));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage() == null ? "null" : e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "timestamp", Instant.now().toString()
        ));
    }
}