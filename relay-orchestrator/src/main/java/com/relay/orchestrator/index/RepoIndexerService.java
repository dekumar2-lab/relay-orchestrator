package com.relay.orchestrator.index;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent; // Presumed domain log event wrapper type
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RepoIndexerService {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexerService.class);
    private final IndexRepository indexRepository;
    private final LogBroadcaster logBroadcaster;

    public RepoIndexerService(IndexRepository indexRepository, LogBroadcaster logBroadcaster) {
        this.indexRepository = indexRepository;
        this.logBroadcaster = logBroadcaster;
    }

    public void indexRepository(String repoId, Path repoPath) throws Exception {
        logBroadcaster.publish(LogEvent.info("Scanning repository details for workspace target: " + repoId + "..."));

        // 1. Clear out historic data rows to enable clean idempotent ingestion runs
        indexRepository.clearRepoIndex(repoId);

        if (!Files.exists(repoPath)) {
            throw new IOException("Target folder path does not exist on filesystem: " + repoPath);
        }

        // 2. Identify all valid Java files across target subdirectory tree branches
        List<Path> javaFiles = Files.walk(repoPath)
                .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                .collect(Collectors.toList());

        logBroadcaster
                .publish(LogEvent.info("Found " + javaFiles.size() + " Java source components to compile and map."));

        int indexedClassesCount = 0;
        int indexedMethodsCount = 0;

        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        for (Path file : javaFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                String relativePath = repoPath.relativize(file).toString();

                // Process standard Classes or Interfaces
                List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
                for (ClassOrInterfaceDeclaration typeDecl : classes) {
                    long classId = parseAndStoreType(repoId, packageName, relativePath, typeDecl);
                    indexedClassesCount++;
                    indexedMethodsCount += parseAndStoreMethods(classId, typeDecl);
                }

                // Process Enums
                List<EnumDeclaration> enums = cu.findAll(EnumDeclaration.class);
                for (EnumDeclaration enumDecl : enums) {
                    long classId = parseAndStoreEnum(repoId, packageName, relativePath, enumDecl);
                    indexedClassesCount++;
                }

            } catch (Exception e) {
                // FAILED processing line catch per spec requirements: Log warning milestone and
                // resume
                String warningMsg = "Parsing failure skipped inside file '" + file.getFileName() + "': "
                        + e.getMessage();
                log.warn(warningMsg);
                logBroadcaster.publish(LogEvent.warn(warningMsg));
            }
        }

        logBroadcaster.publish(LogEvent.success("Indexing pipeline successfully finalized for target [" + repoId
                + "]. Found " + indexedClassesCount + " classes and " + indexedMethodsCount + " methods."));
    }

    private long parseAndStoreType(String repoId, String pkg, String path, ClassOrInterfaceDeclaration decl) {
        String kind = decl.isInterface() ? "interface" : "class";
        List<String> annos = decl.getAnnotations().stream().map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toList());
        String extended = decl.getExtendedTypes().stream().map(t -> t.getNameAsString()).findFirst().orElse("");
        List<String> implemented = decl.getImplementedTypes().stream().map(t -> t.getNameAsString())
                .collect(Collectors.toList());

        return indexRepository.insertClass(repoId, pkg, decl.getNameAsString(), path, kind, annos, extended,
                implemented);
    }

    private long parseAndStoreEnum(String repoId, String pkg, String path, EnumDeclaration decl) {
        List<String> annos = decl.getAnnotations().stream().map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toList());
        return indexRepository.insertClass(repoId, pkg, decl.getNameAsString(), path, "enum", annos, "",
                new ArrayList<>());
    }

    private int parseAndStoreMethods(long classId, ClassOrInterfaceDeclaration decl) {
        List<MethodDeclaration> methods = decl.getMethods();
        for (MethodDeclaration m : methods) {
            List<String> parameters = m.getParameters().stream()
                    .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                    .collect(Collectors.toList());

            int startLine = m.getBegin().map(pos -> pos.line).orElse(0);
            int endLine = m.getEnd().map(pos -> pos.line).orElse(0);

            indexRepository.insertMethod(
                    classId,
                    m.getNameAsString(),
                    m.getSignature().asString(),
                    m.getTypeAsString(),
                    parameters,
                    startLine,
                    endLine,
                    new ArrayList<>() // Dependency code execution trace logic hook placeholder
            );
        }
        return methods.size();
    }
}
