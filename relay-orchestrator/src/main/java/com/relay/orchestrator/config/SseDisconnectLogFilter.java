package com.relay.orchestrator.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import jakarta.annotation.PostConstruct;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.stereotype.Component;

import com.relay.orchestrator.logging.ClientDisconnectDetector;

import java.util.Locale;

@Component
public class SseDisconnectLogFilter extends TurboFilter {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(SseDisconnectLogFilter.class);

    private static final String DISPATCHER_SUFFIX = ".[dispatcherServlet]";

    private static final String[] DISCONNECT_MARKERS = {
            "aborted by the software in your host machine",
            "broken pipe",
            "connection reset",
            "connection aborted",
            "socket closed",
            "software caused connection abort",
            "an existing connection was forcibly closed",
            "connection has been closed"
    };

    @PostConstruct
    public void registerWithLogback() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext ctx) {
            ctx.addTurboFilter(this);
            log.info("SseDisconnectLogFilter registered with Logback");
        } else {
            log.warn("LoggerFactory is not Logback; SSE disconnect noise will not be filtered");
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
            String format, Object[] params, Throwable t) {

        if (level != Level.ERROR || t == null) {
            return FilterReply.NEUTRAL;
        }

        if (logger == null || logger.getName() == null
                || !logger.getName().endsWith(DISPATCHER_SUFFIX)) {
            return FilterReply.NEUTRAL;
        }

        if (isClientDisconnect(t)) {
            return FilterReply.DENY;
        }

        return FilterReply.NEUTRAL;
    }

    private boolean isClientDisconnect(Throwable t) {
    return ClientDisconnectDetector.isDisconnect(t);
}
}