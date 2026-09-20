package com.relay.orchestrator.lang;

import java.util.List;

/**
 * The result of parsing one source file.
 * May contain zero or more CodeUnits (some files have no top-level types).
 */
public record ParsedFile(
        String language,
        String filePath,      // relative to repo root
        String packageOrModule,
        List<CodeUnit> units) {

    public static ParsedFile empty(String language, String filePath) {
        return new ParsedFile(language, filePath, "", List.of());
    }
}