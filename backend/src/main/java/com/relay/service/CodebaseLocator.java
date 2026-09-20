package com.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.model.FileCandidate;
import com.relay.model.IntentTokens;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class CodebaseLocator {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FileCandidate> locate(IntentTokens intent) {
        List<FileCandidate> results = new ArrayList<>();
        try (InputStream in = new ClassPathResource("mock-repos/graphify-report.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            JsonNode projects = root.path("projects");
            JsonNode projectNode = projects.path(intent.getProject());
            JsonNode classes = projectNode.path("classes");

            classes.fieldNames().forEachRemaining(className -> {
                if (intent.getTargetClass() != null && className.equalsIgnoreCase(intent.getTargetClass())) {
                    JsonNode cls = classes.path(className);
                    FileCandidate fc = new FileCandidate(
                            cls.path("filePath").asText(),
                            0.95,
                            "Matched target class: " + className,
                            cls.path("downstreamCount").asInt(0),
                            cls.path("language").asText("java"));
                    results.add(fc);
                }
            });
        } catch (Exception e) {
            System.err.println("CodebaseLocator error: " + e.getMessage());
        }
        return results;
    }
}