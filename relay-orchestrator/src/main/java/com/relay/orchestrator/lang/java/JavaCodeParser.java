package com.relay.orchestrator.lang.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.relay.orchestrator.lang.CodeParser;
import com.relay.orchestrator.lang.CodeUnit;
import com.relay.orchestrator.lang.MethodUnit;
import com.relay.orchestrator.lang.ParsedFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Java parser wrapping JavaParser.
 *
 * Uses a dedicated JavaParser instance rather than StaticJavaParser because
 * StaticJavaParser relies on a shared static ParserConfiguration whose
 * lifecycle is fragile under Spring's bean initialization order. Owning our
 * own ParserConfiguration guarantees the language level we set here is the
 * one actually applied at parse time.
 */
public class JavaCodeParser implements CodeParser {

    private final JavaParser parser;

    public JavaCodeParser() {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(config);
    }

    @Override
    public ParsedFile parse(Path file, Path repoRoot) throws IOException {
        ParseResult<CompilationUnit> result = parser.parse(file);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String problems = result.getProblems().stream()
                    .map(p -> p.getVerboseMessage())
                    .collect(Collectors.joining("; "));
            throw new IOException("JavaParser could not parse " + file.getFileName()
                    + ": " + (problems.isEmpty() ? "unknown reason" : problems));
        }

        CompilationUnit cu = result.getResult().get();
        String relativePath = repoRoot.relativize(file).toString();
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");

        List<CodeUnit> units = new ArrayList<>();

        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            units.add(toCodeUnit(packageName, relativePath, decl));
        }
        for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
            units.add(toEnumCodeUnit(packageName, relativePath, decl));
        }

        return new ParsedFile("java", relativePath, packageName, units);
    }

    private CodeUnit toCodeUnit(String pkg, String path, ClassOrInterfaceDeclaration decl) {
        String kind = decl.isInterface() ? "interface" : "class";
        List<String> annos = decl.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toList());
        String extended = decl.getExtendedTypes().stream()
                .map(t -> t.getNameAsString())
                .findFirst()
                .orElse("");
        List<String> implemented = decl.getImplementedTypes().stream()
                .map(t -> t.getNameAsString())
                .collect(Collectors.toList());

        int lineStart = decl.getBegin().map(p -> p.line).orElse(0);
        int lineEnd = decl.getEnd().map(p -> p.line).orElse(0);

        List<MethodUnit> methods = decl.getMethods().stream()
                .map(this::toMethodUnit)
                .collect(Collectors.toList());

        return new CodeUnit(
                "java",
                pkg,
                decl.getNameAsString(),
                kind,
                path,
                lineStart,
                lineEnd,
                annos,
                extended,
                implemented,
                methods);
    }

    private CodeUnit toEnumCodeUnit(String pkg, String path, EnumDeclaration decl) {
        List<String> annos = decl.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toList());

        int lineStart = decl.getBegin().map(p -> p.line).orElse(0);
        int lineEnd = decl.getEnd().map(p -> p.line).orElse(0);

        return new CodeUnit(
                "java",
                pkg,
                decl.getNameAsString(),
                "enum",
                path,
                lineStart,
                lineEnd,
                annos,
                "",
                List.of(),
                List.of());
    }

    private MethodUnit toMethodUnit(MethodDeclaration m) {
        List<String> parameters = m.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.toList());

        int startLine = m.getBegin().map(p -> p.line).orElse(0);
        int endLine = m.getEnd().map(p -> p.line).orElse(0);

        return new MethodUnit(
                m.getNameAsString(),
                m.getSignature().asString(),
                m.getTypeAsString(),
                parameters,
                startLine,
                endLine,
                List.of());
    }
}