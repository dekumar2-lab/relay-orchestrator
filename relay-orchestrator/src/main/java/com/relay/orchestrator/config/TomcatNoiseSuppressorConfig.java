package com.relay.orchestrator.config;

import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.relay.orchestrator.logging.ClientDisconnectDetector;

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
            return ClientDisconnectDetector.isDisconnect(t);
        }
    }
}