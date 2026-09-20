package com.relay.orchestrator.lang;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Discovers all LanguageSupport implementations and routes files to them
 * by extension.
 *
 * Spring injects every @Service bean that implements LanguageSupport. Adding
 * a new language requires no changes to this class.
 */
@Service
public class LanguageRegistry {

    private static final Logger log = LoggerFactory.getLogger(LanguageRegistry.class);

    private final Map<String, LanguageSupport> byExtension = new HashMap<>();
    private final List<LanguageSupport> allLanguages;

    public LanguageRegistry(List<LanguageSupport> languages) {
        this.allLanguages = languages;
        for (LanguageSupport lang : languages) {
            for (String ext : lang.fileExtensions()) {
                String normalized = normalize(ext);
                LanguageSupport existing = byExtension.put(normalized, lang);
                if (existing != null) {
                    log.warn("Extension {} claimed by both {} and {} - using {}",
                            normalized, existing.id(), lang.id(), lang.id());
                }
            }
            log.info("Registered language: {} (extensions: {})",
                    lang.id(), lang.fileExtensions());
        }
    }

    /** Look up the language for a given file path. */
    public Optional<LanguageSupport> forFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String ext = name.substring(dot);
        return Optional.ofNullable(byExtension.get(ext));
    }

    /** True if any registered language claims this extension. */
    public boolean isSupported(Path file) {
        return forFile(file).isPresent();
    }

    /** All registered languages. Useful for repo detection and UI. */
    public List<LanguageSupport> allLanguages() {
        return List.copyOf(allLanguages);
    }

    private String normalize(String ext) {
        String lower = ext.toLowerCase(Locale.ROOT);
        return lower.startsWith(".") ? lower : "." + lower;
    }
}