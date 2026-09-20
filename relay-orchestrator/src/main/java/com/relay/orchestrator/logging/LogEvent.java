package com.relay.orchestrator.logging;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A single line in the live log tail. Every future phase (connection
 * validation, repo indexing, story analysis, pipeline execution) publishes
 * through the same {@link LogBroadcaster} using this shape, so the log tail
 * UI never needs to change as new features are added.
 */
public record LogEvent(LocalTime timestamp, Level level, String message) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SS");

    public enum Level {
        INFO, WARN, ERROR, SUCCESS
    }

    public static LogEvent now(Level level, String message) {
        return new LogEvent(LocalTime.now(), level, message);
    }

    public static LogEvent info(String message) {
        return now(Level.INFO, message);
    }

    public static LogEvent warn(String message) {
        return now(Level.WARN, message);
    }

    public static LogEvent error(String message) {
        return now(Level.ERROR, message);
    }

    public static LogEvent success(String message) {
        return now(Level.SUCCESS, message);
    }

    /** Renders this event as the small HTML row the log panel appends via SSE. */
    public String toHtmlRow() {
        String levelClass = switch (level) {
            case INFO -> "text-slate-400";
            case WARN -> "text-amber-400";
            case ERROR -> "text-red-400";
            case SUCCESS -> "text-emerald-400";
        };
        String escaped = message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return """
                <div class="font-mono text-sm leading-6 whitespace-pre-wrap break-words">\
                <span class="text-slate-600">%s</span> \
                <span class="%s font-semibold">[%s]</span> \
                <span class="text-slate-200">%s</span></div>"""
                .formatted(timestamp.format(TIME_FORMAT), levelClass, level, escaped);
    }
}
