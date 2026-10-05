package com.relay.orchestrator.pipeline.impl;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class WorkspaceSessionStore {

    private final Map<String, List<StagedChange>> stagedBySession = new ConcurrentHashMap<>();

    public void reset(String sessionId) {
        stagedBySession.put(sessionId, new CopyOnWriteArrayList<>());
    }

    public List<StagedChange> staged(String sessionId) {
        return stagedBySession.computeIfAbsent(sessionId,
                k -> new CopyOnWriteArrayList<>());
    }

    /**
     * Add a staged change. If a change for the same path already exists,
     * it is REPLACED. This prevents duplicate entries when Jim writes a
     * file, then edits it again in the same run.
     */
    public void add(String sessionId, StagedChange change) {
        List<StagedChange> list = staged(sessionId);
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).path().equals(change.path())) {
                    list.set(i, change);
                    return;
                }
            }
            list.add(change);
        }
    }

    /** Remove a staged change by path. Used by the UI's "Remove file" action. */
    public boolean remove(String sessionId, String path) {
        List<StagedChange> list = staged(sessionId);
        synchronized (list) {
            return list.removeIf(c -> c.path().equals(path));
        }
    }

    public void clear(String sessionId) {
        stagedBySession.remove(sessionId);
    }
}