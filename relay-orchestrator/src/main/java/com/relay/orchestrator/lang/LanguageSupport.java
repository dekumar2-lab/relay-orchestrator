package com.relay.orchestrator.lang;

import java.nio.file.Path;
import java.util.Set;

/**
 * Everything the indexer needs to know about one programming language.
 *
 * Adding a new language means:
 *   1. Implement LanguageSupport.
 *   2. Annotate with @Service so LanguageRegistry picks it up.
 *   3. Done. RepoIndexerService needs no changes.
 */
public interface LanguageSupport {

    /** Short identifier used in logs, DB rows, and language column. E.g. "java". */
    String id();

    /** File extensions this language handles, lowercase with leading dot. E.g. ".java". */
    Set<String> fileExtensions();

    /** The parser that converts source files into ParsedFile records. */
    CodeParser parser();

    /**
     * Heuristic: does this repo look like it contains this language?
     * Used when auto-detecting languages for a repo. Default returns false.
     */
    default boolean detect(Path repoRoot) {
        return false;
    }
}