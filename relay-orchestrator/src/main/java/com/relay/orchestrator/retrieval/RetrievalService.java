package com.relay.orchestrator.retrieval;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BM25 retrieval over the Lucene index built by LuceneIndexService.
 *
 * Scores are normalized to [0,1] within each result set (top hit = 1.0).
 * This preserves the RetrievedChunk.similarity contract; callers should
 * not treat it as an absolute quality measure.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private static final String[] SEARCH_FIELDS = { "content", "nameIndexed" };

    // Class/method names are strong signal; content is weaker.
    private static final Map<String, Float> FIELD_BOOSTS = Map.of(
            "content", 1.0f,
            "nameIndexed", 3.0f);

    private static final int DEFAULT_TOP_K = 10;
    private static final float DEFAULT_SCORE_FLOOR = 0.0f;
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8_000;

    private final LuceneIndexService luceneIndex;

    public RetrievalService(LuceneIndexService luceneIndex) {
        this.luceneIndex = luceneIndex;
    }

    public List<RetrievedChunk> retrieveForStory(String story) {
        return retrieveForStory(story, DEFAULT_TOP_K,
                DEFAULT_SCORE_FLOOR, DEFAULT_MAX_CONTEXT_TOKENS);
    }

    public List<RetrievedChunk> retrieveForStory(String story,
            int topK,
            float scoreFloor,
            int maxContextTokens) {
        if (story == null || story.isBlank())
            return List.of();
        if (!luceneIndex.isReady()) {
            log.debug("Lucene index not ready; returning empty results");
            return List.of();
        }

        IndexSearcher searcher = luceneIndex.searcher();
        Analyzer analyzer = luceneIndex.analyzer();

        MultiFieldQueryParser parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer, FIELD_BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);

        Query query;
        try {
            query = parser.parse(QueryParser.escape(story));
        } catch (ParseException e) {
            log.warn("Failed to parse story into Lucene query: {}", e.getMessage());
            return List.of();
        }

        TopDocs topDocs;
        try {
            // Oversample, then trim by score floor and token budget.
            topDocs = searcher.search(query, Math.max(1, topK * 3));
        } catch (IOException e) {
            log.error("Lucene search failed", e);
            return List.of();
        }

        if (topDocs.scoreDocs.length == 0) {
            log.debug("BM25 returned zero hits for story");
            return List.of();
        }

        float maxScore = topDocs.scoreDocs[0].score;
        List<RetrievedChunk> results = new ArrayList<>();
        int runningTokens = 0;

        for (ScoreDoc sd : topDocs.scoreDocs) {
            if (results.size() >= topK)
                break;

            Document doc;
            try {
                doc = searcher.storedFields().document(sd.doc);
            } catch (IOException e) {
                continue;
            }

            float normalized = maxScore > 0f ? sd.score / maxScore : 0f;
            if (normalized < scoreFloor)
                continue;

            CodeChunk chunk = new CodeChunk(
                    doc.get("repoId"),
                    doc.get("chunkType"),
                    doc.get("qualifiedName"),
                    doc.get("filePath"),
                    doc.get("content"));

            int chunkTokens = estimateTokens(chunk.content());
            if (runningTokens + chunkTokens > maxContextTokens)
                break;
            runningTokens += chunkTokens;

            results.add(new RetrievedChunk(chunk, normalized));
        }

        log.debug("BM25 retrieved {} chunks, ~{} tokens", results.size(), runningTokens);
        return results;
    }

    private int estimateTokens(String text) {
        if (text == null)
            return 0;
        return Math.max(1, text.length() / 4);
    }
}