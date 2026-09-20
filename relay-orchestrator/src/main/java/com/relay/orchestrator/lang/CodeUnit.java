package com.relay.orchestrator.lang;

import java.util.List;

/**
 * One type declaration (class / interface / enum / struct / module / etc.)
 * extracted from a source file. Language-neutral.
 */
public record CodeUnit(
        String language,
        String packageOrModule,
        String simpleName,
        String kind,               // "class", "interface", "enum", "function", "module"
        String filePath,           // relative to repo root
        int lineStart,
        int lineEnd,
        List<String> annotations,
        String extendsType,        // "" if none
        List<String> implementsTypes,
        List<MethodUnit> methods) {

    public int methodCount() {
        return methods == null ? 0 : methods.size();
    }
}