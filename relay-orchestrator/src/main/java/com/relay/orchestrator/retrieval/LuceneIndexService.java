package com.relay.orchestrator.retrieval;

import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory Lucene index over code_chunks. SQLite remains the source of
 * truth; this index is rebuilt from it whenever a repo finishes indexing.
 *
 * Rebuild strategy: full rebuild. Fast for our corpus sizes (thousands of
 * chunks). The searcher reference is swapped atomically so concurrent
 * readers never see a half-built index.
 */
@Service
public class LuceneIndexService {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexService.class);

    private final ChunkRepository chunkRepository;
    private final LogBroadcaster logBroadcaster;

    private final Analyzer analyzer = new StandardAnalyzer();
    private final AtomicReference<IndexSearcher> searcherRef = new AtomicReference<>();
    private final AtomicReference<DirectoryReader> readerRef = new AtomicReference<>();

    public LuceneIndexService(ChunkRepository chunkRepository,
            LogBroadcaster logBroadcaster) {
        this.chunkRepository = chunkRepository;
        this.logBroadcaster = logBroadcaster;
    }

    /** Full rebuild from SQLite. Safe to call repeatedly. */
    public synchronized void rebuildIndex() {
        Directory directory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        int count = 0;
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (CodeChunk chunk : chunkRepository.findAll()) {
                Document doc = new Document();
                doc.add(new StringField("repoId", nz(chunk.repoId()), Field.Store.YES));
                doc.add(new StringField("chunkType", nz(chunk.chunkType()), Field.Store.YES));
                doc.add(new StringField("qualifiedName", nz(chunk.qualifiedName()), Field.Store.YES));
                doc.add(new StringField("filePath", nz(chunk.filePath()), Field.Store.YES));
                // Two versions of the name: stored for display, analyzed for search.
                doc.add(new TextField("content", nz(chunk.content()), Field.Store.YES));
                doc.add(new TextField("nameIndexed", nz(chunk.qualifiedName()), Field.Store.NO));
                writer.addDocument(doc);
                count++;
            }
        } catch (IOException e) {
            log.error("Failed to build Lucene index", e);
            logBroadcaster.publish(LogEvent.error(
                    "[RETRIEVAL] Failed to build Lucene index: " + e.getMessage()));
            return;
        }

        try {
            DirectoryReader newReader = DirectoryReader.open(directory);
            DirectoryReader oldReader = readerRef.getAndSet(newReader);
            searcherRef.set(new IndexSearcher(newReader));
            if (oldReader != null)
                oldReader.close();
        } catch (IOException e) {
            log.error("Failed to open Lucene reader", e);
            return;
        }

        log.info("Lucene index rebuilt: {} documents", count);
        logBroadcaster.publish(LogEvent.info(
                "[RETRIEVAL] Lucene index rebuilt: " + count + " chunks"));
    }

    public boolean isReady() {
        return searcherRef.get() != null;
    }

    public IndexSearcher searcher() {
        return searcherRef.get();
    }

    public Analyzer analyzer() {
        return analyzer;
    }

    @PreDestroy
    public void close() {
        try {
            DirectoryReader reader = readerRef.getAndSet(null);
            if (reader != null)
                reader.close();
        } catch (IOException ignored) {
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        int total = chunkRepository.countAll();
        if (total > 0) {
            log.info("Rebuilding Lucene index from {} existing chunks", total);
            rebuildIndex();
        } else {
            log.info("No chunks in DB; Lucene index stays empty until first index run");
        }
    }
}