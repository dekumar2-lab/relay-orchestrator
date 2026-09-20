package com.relay.orchestrator.lang;

import java.util.List;

/**
 * One method/function extracted from a CodeUnit. Language-neutral.
 */
public record MethodUnit(
        String name,
        String signature,
        String returnType,
        List<String> parameters,   // formatted as "TypeName paramName"
        int lineStart,
        int lineEnd,
        List<String> callsOut) {   // placeholder for future call-graph extraction
}