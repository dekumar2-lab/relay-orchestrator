package com.relay.orchestrator.logging;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for "is this exception just a client that went away?"
 * Previously duplicated across LogBroadcaster, SseDisconnectLogFilter, and
 * SilentDisconnectValve with drifting marker lists.
 */
public final class ClientDisconnectDetector {

    private ClientDisconnectDetector() {}

    private static final Set<String> MARKERS = Set.of(
            "aborted by the software in your host machine",
            "broken pipe",
            "connection reset",
            "connection aborted",
            "socket closed",
            "software caused connection abort",
            "an existing connection was forcibly closed",
            "connection has been closed",
            "connection closed",
            "disconnected",
            "responsebodyemitter has already completed");

    public static boolean isDisconnect(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                for (String m : MARKERS) {
                    if (lower.contains(m)) return true;
                }
            }
            String cn = cur.getClass().getName();
            if (cn != null && cn.toLowerCase(Locale.ROOT).contains("clientabortexception")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}