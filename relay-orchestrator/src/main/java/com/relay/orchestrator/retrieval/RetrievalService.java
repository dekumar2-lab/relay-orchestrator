package com.relay.orchestrator.retrieval;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25 retrieval over the Lucene index built by LuceneIndexService.
 *
 * Two query components are combined:
 * 1. General — MultiFieldQueryParser over the whole story, OR semantics.
 * 2. Identifier boost — any CamelCase token in the story is turned into a
 * boosted TermQuery against nameIndexed. This makes "PipelineContext is
 * broken" retrieve PipelineContext chunks even when the rest of the
 * story contributes no useful tokens.
 *
 * Scores are normalized to [0,1] within each result set (top hit = 1.0).
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private static final String[] SEARCH_FIELDS = { "content", "nameIndexed" };

    private static final Map<String, Float> FIELD_BOOSTS = Map.of(
            "content", 1.0f,
            "nameIndexed", 3.0f);

    private static final int DEFAULT_TOP_K = 10;
    private static final float DEFAULT_SCORE_FLOOR = 0.0f;
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8_000;

    /** How much to boost a CamelCase identifier match on nameIndexed. */
    private static final float IDENTIFIER_BOOST = 8.0f;

    /** Cap on how many identifiers we extract per query. */
    private static final int MAX_IDENTIFIERS = 6;

    /**
     * Regex for Java-ish identifiers: starts with uppercase, has at least
     * two more chars. Post-filtered to reject common English words that
     * happen to start with a capital letter.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\b[A-Z][a-zA-Z0-9]{2,}\\b");

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

        Set<String> identifiers = extractIdentifiers(story);

        Query query;
        try {
            Query general = buildGeneralQuery(story, analyzer);
            Query identifierBoost = buildIdentifierQuery(identifiers);

            if (identifierBoost != null) {
                BooleanQuery.Builder combined = new BooleanQuery.Builder();
                combined.add(general, BooleanClause.Occur.SHOULD);
                combined.add(identifierBoost, BooleanClause.Occur.SHOULD);
                query = combined.build();
                log.debug("Query: general + identifier boost {}", identifiers);
            } else {
                query = general;
            }
        } catch (ParseException e) {
            log.warn("Failed to parse story into Lucene query: {}", e.getMessage());
            return List.of();
        }

        TopDocs topDocs;
        try {
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

    // ------------------------------------------------------------------
    // Query building
    // ------------------------------------------------------------------

    private Query buildGeneralQuery(String story, Analyzer analyzer) throws ParseException {
        MultiFieldQueryParser parser = new MultiFieldQueryParser(
                SEARCH_FIELDS, analyzer, FIELD_BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        return parser.parse(QueryParser.escape(story));
    }

    /**
     * Build a BooleanQuery of boosted TermQuery clauses against nameIndexed.
     * StandardAnalyzer lowercases tokens and does NOT split CamelCase, so
     * "PipelineContext" indexes as the single token "pipelinecontext" — the
     * exact token we query for.
     *
     * Returns null if there are no identifiers.
     */
    private Query buildIdentifierQuery(Set<String> identifiers) {
        if (identifiers.isEmpty())
            return null;

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String id : identifiers) {
            String token = id.toLowerCase(Locale.ROOT);
            // PrefixQuery on the keyword field matches both the class chunk
            // ("sseemitterservice") and every method chunk under it
            // ("sseemitterservice.emit", ".subscribe", ...).
            PrefixQuery pq = new PrefixQuery(new Term("nameSimple", token));
            builder.add(new BoostQuery(pq, IDENTIFIER_BOOST), BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    /**
     * Pull CamelCase identifiers out of the story.
     *
     * Heuristic: 2+ capitals OR length >= 8. This keeps real class names
     * (PipelineContext, TokenBudgetGuard, ApplyService) and rejects sentence-
     * initial words (Fix, Add, The, This).
     *
     * Capped at MAX_IDENTIFIERS so a story that mentions ten classes does
     * not blow up the query.
     */
    private Set<String> extractIdentifiers(String story) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = IDENTIFIER_PATTERN.matcher(story);
        while (m.find()) {
            String candidate = m.group();

            int capitals = 0;
            for (int i = 0; i < candidate.length(); i++) {
                if (Character.isUpperCase(candidate.charAt(i)))
                    capitals++;
            }
            boolean plausible = capitals >= 2 || candidate.length() >= 8;
            if (!plausible)
                continue;

            ids.add(candidate);
            if (ids.size() >= MAX_IDENTIFIERS)
                break;
        }
        return ids;
    }

    private int estimateTokens(String text) {
        if (text == null)
            return 0;
        return Math.max(1, text.length() / 4);
    }
}