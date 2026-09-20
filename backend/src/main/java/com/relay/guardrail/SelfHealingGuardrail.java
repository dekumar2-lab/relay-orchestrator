package com.relay.guardrail;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class SelfHealingGuardrail {

    private final List<Integer> recentHashes = new ArrayList<>();

    public boolean isLoop(String errorLog) {
        if (errorLog == null)
            return false;
        int hash = errorLog.hashCode();
        if (!recentHashes.isEmpty()
                && recentHashes.get(recentHashes.size() - 1) == hash) {
            return true;
        }
        recentHashes.add(hash);
        if (recentHashes.size() > 5)
            recentHashes.remove(0);
        return false;
    }

    public void reset() {
        recentHashes.clear();
    }
}