package com.relay.orchestrator.config;

/**
 * Published by AppConfigManager.updateFrom() after a successful save.
 * Listeners use it to invalidate caches that were derived from config
 * (currently: AgentRegistry's persona cache).
 */
public record ConfigChangedEvent() {
}