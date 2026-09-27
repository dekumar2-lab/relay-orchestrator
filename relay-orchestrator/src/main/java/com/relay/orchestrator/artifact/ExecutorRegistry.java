package com.relay.orchestrator.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Spring injects every bean that implements Executor. The registry maps
 * ArtifactKind → Executor so callers look up by kind and never branch
 * on class names.
 */
@Service
public class ExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistry.class);

    private final Map<ArtifactKind, Executor> byKind = new EnumMap<>(ArtifactKind.class);

    public ExecutorRegistry(List<Executor> executors) {
        for (Executor e : executors) {
            Executor existing = byKind.put(e.consumes(), e);
            if (existing != null) {
                log.warn("Duplicate executor for {}: {} replaced {}",
                        e.consumes(), e.getClass().getSimpleName(),
                        existing.getClass().getSimpleName());
            }
        }
        log.info("ExecutorRegistry: {} executor(s) registered: {}",
                byKind.size(), byKind.keySet());
    }

    public Executor forKind(ArtifactKind kind) {
        Executor e = byKind.get(kind);
        if (e == null) {
            throw new IllegalStateException(
                    "No executor registered for " + kind);
        }
        return e;
    }

    public boolean has(ArtifactKind kind) {
        return byKind.containsKey(kind);
    }
}