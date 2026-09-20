package com.relay.orchestrator.lang;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Converts one source file into a ParsedFile record.
 * Implementations are language-specific but the output shape is neutral.
 */
public interface CodeParser {

    /**
     * @param file      the source file to parse
     * @param repoRoot  the repo root, used to compute the relative path
     * @return parsed representation, or an empty ParsedFile if the file has no parseable content
     * @throws IOException on I/O error reading the file
     */
    ParsedFile parse(Path file, Path repoRoot) throws IOException;
}