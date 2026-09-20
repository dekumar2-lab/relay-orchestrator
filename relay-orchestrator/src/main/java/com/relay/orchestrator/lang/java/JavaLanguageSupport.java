package com.relay.orchestrator.lang.java;

import com.relay.orchestrator.lang.CodeParser;
import com.relay.orchestrator.lang.LanguageSupport;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Service
public class JavaLanguageSupport implements LanguageSupport {

    private final JavaCodeParser parser = new JavaCodeParser();

    @Override
    public String id() {
        return "java";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of(".java");
    }

    @Override
    public CodeParser parser() {
        return parser;
    }

    @Override
    public boolean detect(Path repoRoot) {
        return Files.exists(repoRoot.resolve("pom.xml"))
                || Files.exists(repoRoot.resolve("build.gradle"))
                || Files.exists(repoRoot.resolve("build.gradle.kts"));
    }
}