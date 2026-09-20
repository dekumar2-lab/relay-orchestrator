package com.relay.orchestrator.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Logback TurboFilter that swallows the "Servlet.service() for servlet
 * dispatcherServlet threw exception" ERROR entries when the underlying cause
 * is a client disconnect on an SSE stream.
 *
 * Runs BEFORE the log event is formatted, so there is zero console noise
 * and zero async-dispatch reentrancy. The counterpart to the Tomcat
 * ErrorReportValve, which does not always fire on Spring Boot 3.4's async
 * dispatch path.
 *
 * Scope: only the 'dispatcherServlet' logger at ERROR level is filtered, and
 * only when the throwable is a recognised client-disconnect IOException.
 * Everything else passes through untouched.
 */
@Component
public class SseDisconnectLogFilter extends TurboFilter {

    private static final String DISPATCHER_SERVLET_LOGGER =
            "o.a.c.c.C.[.[.[/].[dispatcherServlet]";

    private static final String[] DISCONNECT_MARKERS = {
            "aborted by the software in your host machine",
            "broken pipe",
            "connection reset",
            "connection aborted",
            "socket closed",
            "software caused connection abort",
            "an existing connection was forcibly closed"
    };

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
            String format, Object[] params, Throwable t) {

        if (level != Level.ERROR || t == null) {
            return FilterReply.NEUTRAL;
        }

        if (!DISPATCHER_SERVLET_LOGGER.equals(logger.getName())) {
            return FilterReply.NEUTRAL;
        }

        if (isClientDisconnect(t)) {
            return FilterReply.DENY;
        }

        return FilterReply.NEUTRAL;
    }

    private boolean isClientDisconnect(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                for (String marker : DISCONNECT_MARKERS) {
                    if (lower.contains(marker)) {
                        return true;
                    }
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}