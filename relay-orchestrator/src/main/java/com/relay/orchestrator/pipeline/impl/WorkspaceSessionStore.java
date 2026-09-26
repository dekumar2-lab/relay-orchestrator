package com.relay.orchestrator.pipeline.impl;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory staging area for proposed changes, keyed by session id.
 * Lost on restart — acceptable for Phase 2a since the diff is also
 * serialized into PipelineSession for persistence.
 */
@Service
public class WorkspaceSessionStore {

    private final Map<String, List<StagedChange>> stagedBySession = new ConcurrentHashMap<>();

    public void reset(String sessionId) {
        stagedBySession.put(sessionId, new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    public List<StagedChange> staged(String sessionId) {
        return stagedBySession.computeIfAbsent(sessionId,
                k -> new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    public void add(String sessionId, StagedChange change) {
        staged(sessionId).add(change);
    }

    public void clear(String sessionId) {
        stagedBySession.remove(sessionId);
    }
}