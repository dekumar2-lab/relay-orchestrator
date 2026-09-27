package com.relay.orchestrator.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Spring injects every bean that implements Producer. The registry maps
 * ArtifactKind → Producer so callers look up by kind and never branch on
 * class names.
 *
 * Adding a new producer is one @Service class. Nothing here changes.
 */
@Service
public class ProducerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProducerRegistry.class);

    private final Map<ArtifactKind, Producer> byKind = new EnumMap<>(ArtifactKind.class);

    public ProducerRegistry(List<Producer> producers) {
        for (Producer p : producers) {
            Producer existing = byKind.put(p.produces(), p);
            if (existing != null) {
                log.warn("Duplicate producer for {}: {} replaced {}",
                        p.produces(), p.getClass().getSimpleName(),
                        existing.getClass().getSimpleName());
            }
        }
        log.info("ProducerRegistry: {} producer(s) registered: {}",
                byKind.size(), byKind.keySet());
    }

    public Producer forKind(ArtifactKind kind) {
        Producer p = byKind.get(kind);
        if (p == null) {
            throw new IllegalStateException(
                    "No producer registered for " + kind);
        }
        return p;
    }

    public boolean has(ArtifactKind kind) {
        return byKind.containsKey(kind);
    }
}