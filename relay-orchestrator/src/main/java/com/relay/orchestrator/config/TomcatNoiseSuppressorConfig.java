package com.relay.orchestrator.config;

import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatNoiseSuppressorConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> suppressSseDisconnectNoise() {
        return factory -> factory.addContextCustomizers(context -> {
            // Replace the default error reporting valve with one that silences
            // client-disconnect IOExceptions on SSE streams.
            context.getParent(); // ensure parent initialized
            ErrorReportValve silent = new SilentDisconnectValve();
            context.getPipeline().addValve(silent);
        });
    }

    static class SilentDisconnectValve extends ErrorReportValve {
        @Override
        protected void report(org.apache.catalina.connector.Request request,
                              org.apache.catalina.connector.Response response,
                              java.lang.Throwable throwable) {
            if (throwable != null && isClientDisconnect(throwable)) {
                return; // swallow silently
            }
            super.report(request, response, throwable);
        }

        private boolean isClientDisconnect(Throwable t) {
            Throwable cur = t;
            while (cur != null) {
                String msg = cur.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("aborted by the software in your host machine")
                            || lower.contains("broken pipe")
                            || lower.contains("connection reset")
                            || lower.contains("connection aborted")
                            || lower.contains("socket closed")
                            || lower.contains("software caused connection abort")) {
                        return true;
                    }
                }
                cur = cur.getCause();
            }
            return false;
        }
    }
}