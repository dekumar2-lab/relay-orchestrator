package com.relay.orchestrator.retrieval;

import com.relay.orchestrator.lang.CodeUnit;
import com.relay.orchestrator.lang.MethodUnit;
import com.relay.orchestrator.lang.ParsedFile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a ParsedFile into a list of retrievable CodeChunks.
 *
 * Two chunk types:
 * - Class chunk: package, name, kind, annotations, extends/implements,
 * list of method names. Good for "what does this class do?"
 * - Method chunk: one per method, with signature, parameters, return type,
 * and calls-out. Good for "where is X implemented?"
 *
 * Content is stripped down to natural-language-ish text so MiniLM's semantic
 * signal isn't diluted by raw code tokens like braces and semicolons.
 */
@Service
public class ChunkBuilder {

    public List<CodeChunk> build(String repoId, ParsedFile parsed) {
        List<CodeChunk> chunks = new ArrayList<>();

        for (CodeUnit unit : parsed.units()) {
            chunks.add(buildClassChunk(repoId, parsed, unit));

            for (MethodUnit method : unit.methods()) {
                chunks.add(buildMethodChunk(repoId, parsed, unit, method));
            }
        }
        return chunks;
    }

    // ----------------------------------------------------------------
    // Class chunk
    // ----------------------------------------------------------------

    private CodeChunk buildClassChunk(String repoId, ParsedFile parsed, CodeUnit unit) {
        StringBuilder sb = new StringBuilder();

        append(sb, "Type", "class");
        append(sb, "Language", parsed.language());
        append(sb, "Package", unit.packageOrModule());
        append(sb, "Name", unit.simpleName());
        append(sb, "Kind", unit.kind());
        appendCsv(sb, "Annotations", unit.annotations());
        append(sb, "Extends", unit.extendsType());
        appendCsv(sb, "Implements", unit.implementsTypes());
        append(sb, "File", parsed.filePath());

        if (!unit.methods().isEmpty()) {
            List<String> names = unit.methods().stream()
                    .map(MethodUnit::name)
                    .toList();
            appendCsv(sb, "Methods", names);
        }

        String qualified = qualifiedName(unit.packageOrModule(), unit.simpleName());

        return new CodeChunk(
                repoId,
                "class",
                qualified,
                parsed.filePath(),
                sb.toString().trim());
    }

    // ----------------------------------------------------------------
    // Method chunk
    // ----------------------------------------------------------------

    private CodeChunk buildMethodChunk(String repoId, ParsedFile parsed,
            CodeUnit owner, MethodUnit method) {
        StringBuilder sb = new StringBuilder();

        append(sb, "Type", "method");
        append(sb, "Language", parsed.language());
        append(sb, "Class", qualifiedName(owner.packageOrModule(), owner.simpleName()));
        append(sb, "Name", method.name());
        append(sb, "Signature", method.signature());
        appendCsv(sb, "Parameters", method.parameters());
        append(sb, "Returns", method.returnType());
        appendCsv(sb, "Calls", method.callsOut());

        String qualifiedClass = qualifiedName(owner.packageOrModule(), owner.simpleName());
        String qualifiedMethod = qualifiedClass + "." + method.name();

        return new CodeChunk(
                repoId,
                "method",
                qualifiedMethod,
                parsed.filePath(),
                sb.toString().trim());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String qualifiedName(String packageOrModule, String simpleName) {
        if (packageOrModule == null || packageOrModule.isBlank()) {
            return simpleName;
        }
        return packageOrModule + "." + simpleName;
    }

    private void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank())
            return;
        sb.append(key).append(": ").append(value).append('\n');
    }

    private void appendCsv(StringBuilder sb, String key, List<String> values) {
        if (values == null || values.isEmpty())
            return;
        sb.append(key).append(": ").append(String.join(", ", values)).append('\n');
    }
}