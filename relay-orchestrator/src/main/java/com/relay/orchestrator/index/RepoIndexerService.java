package com.relay.orchestrator.index;

import com.relay.orchestrator.lang.CodeUnit;
import com.relay.orchestrator.lang.LanguageRegistry;
import com.relay.orchestrator.lang.LanguageSupport;
import com.relay.orchestrator.lang.MethodUnit;
import com.relay.orchestrator.lang.ParsedFile;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.retrieval.ChunkBuilder;
import com.relay.orchestrator.retrieval.ChunkRepository;
import com.relay.orchestrator.retrieval.CodeChunk;
import com.relay.orchestrator.retrieval.LuceneIndexService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class RepoIndexerService {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexerService.class);

    private final IndexRepository indexRepository;
    private final LanguageRegistry languageRegistry;
    private final LogBroadcaster logBroadcaster;
    private final ChunkBuilder chunkBuilder;
    private final ChunkRepository chunkRepository;
    private final LuceneIndexService luceneIndexService;

    public RepoIndexerService(IndexRepository indexRepository,
            LanguageRegistry languageRegistry,
            LogBroadcaster logBroadcaster,
            ChunkBuilder chunkBuilder,
            ChunkRepository chunkRepository,
            LuceneIndexService luceneIndexService) {
        this.indexRepository = indexRepository;
        this.languageRegistry = languageRegistry;
        this.logBroadcaster = logBroadcaster;
        this.chunkBuilder = chunkBuilder;
        this.chunkRepository = chunkRepository;
        this.luceneIndexService = luceneIndexService;
    }

    public void indexRepository(String repoId, Path repoPath) throws Exception {
        logBroadcaster.publish(LogEvent.info(
                "Scanning repository details for workspace target: " + repoId + "..."));

        indexRepository.clearRepoIndex(repoId);

        if (!Files.exists(repoPath)) {
            throw new IOException("Target folder path does not exist on filesystem: " + repoPath);
        }

        List<Path> candidateFiles;
        try (Stream<Path> walk = Files.walk(repoPath)) {
            candidateFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(languageRegistry::isSupported)
                    .toList();
        }

        logBroadcaster.publish(LogEvent.info(
                "Found " + candidateFiles.size() + " source files matching registered languages."));

        int indexedClasses = 0;
        int indexedMethods = 0;
        int indexedChunks = 0;
        long startNanos = System.nanoTime();

        for (Path file : candidateFiles) {
            Optional<LanguageSupport> maybeLang = languageRegistry.forFile(file);
            if (maybeLang.isEmpty()) {
                continue;
            }
            LanguageSupport language = maybeLang.get();

            try {
                ParsedFile parsed = language.parser().parse(file, repoPath);
                StoreCounts counts = storeParsedFile(repoId, parsed);
                indexedClasses += counts.classes;
                indexedMethods += counts.methods;
                indexedChunks += storeChunks(repoId, parsed);
            } catch (Exception e) {
                String warningMsg = "Parsing failure skipped inside file '" + file.getFileName()
                        + "' (" + language.id() + "): " + e.getMessage();
                log.warn(warningMsg, e);
                logBroadcaster.publish(LogEvent.warn(warningMsg));
            }
        }

        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;

        logBroadcaster.publish(LogEvent.success(
                "Indexing pipeline successfully finalized for target [" + repoId
                        + "]. Found " + indexedClasses + " classes, "
                        + indexedMethods + " methods, "
                        + indexedChunks + " chunks stored in " + totalMs + " ms."));

        // NEW: refresh the BM25 index so retrieval can see the fresh chunks.
        luceneIndexService.rebuildIndex();
    }

    private int storeChunks(String repoId, ParsedFile parsed) {
        List<CodeChunk> chunks = chunkBuilder.build(repoId, parsed);
        int stored = 0;
        for (CodeChunk chunk : chunks) {
            try {
                chunkRepository.insert(chunk);
                stored++;
            } catch (Exception e) {
                log.warn("Failed to store chunk {}: {}", chunk.qualifiedName(), e.getMessage());
            }
        }
        return stored;
    }

    private StoreCounts storeParsedFile(String repoId, ParsedFile parsed) {
        int classes = 0;
        int methods = 0;

        for (CodeUnit unit : parsed.units()) {
            long classId = indexRepository.insertClass(
                    repoId,
                    unit.packageOrModule(),
                    unit.simpleName(),
                    unit.filePath(),
                    unit.kind(),
                    unit.annotations(),
                    unit.extendsType(),
                    unit.implementsTypes());

            classes++;

            for (MethodUnit m : unit.methods()) {
                indexRepository.insertMethod(
                        classId,
                        m.name(),
                        m.signature(),
                        m.returnType(),
                        m.parameters(),
                        m.lineStart(),
                        m.lineEnd(),
                        m.callsOut());
                methods++;
            }
        }
        return new StoreCounts(classes, methods);
    }

    private record StoreCounts(int classes, int methods) {
    }
}