package com.relay.orchestrator.retrieval;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local embedding service backed by ONNX Runtime.
 *
 * Produces 384-dimensional L2-normalized embeddings using
 * sentence-transformers/all-MiniLM-L6-v2.
 *
 * Model loading is lazy and non-fatal. If the model can't load (offline,
 * missing files, incompatible runtime), embed() throws EmbeddingUnavailable,
 * and the caller (RetrievalService) degrades to "no retrieval" gracefully.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final int EMBEDDING_DIM = 384;
    private static final int MAX_SEQ_LEN = 256;

    private final ModelProvisioner provisioner;

    private volatile boolean available = false;
    private volatile String unavailableReason = "not initialized";

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    // Resolved at load time from session metadata (models vary)
    private String inputIdsName;
    private String attentionMaskName;
    private String tokenTypeIdsName;
    private String outputName;

    public EmbeddingService(ModelProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @PostConstruct
    public void init() {
        // Defer heavy loading — first call to embed() triggers load.
        // Keeps app startup fast and lets retrieval stay optional.
        log.info("EmbeddingService wired — model will load on first embed() call");
        log.info("Model directory: {}", provisioner.modelDir());
        log.info("To pre-load for offline use, place model files in that directory");
    }

    public boolean isAvailable() {
        if (!available)
            ensureLoaded();
        return available;
    }

    public String unavailableReason() {
        return unavailableReason;
    }

    public int dimension() {
        return EMBEDDING_DIM;
    }

    /** Embed a piece of text into a unit vector. */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new EmbeddingUnavailable("Cannot embed empty text");
        }
        if (!available)
            ensureLoaded();
        if (!available) {
            throw new EmbeddingUnavailable(unavailableReason);
        }

        try {
            Encoding encoding = tokenizer.encode(text);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();

            // Truncate to MAX_SEQ_LEN — MiniLM supports longer, but we cap for
            // consistent latency and bounded memory.
            int len = Math.min(ids.length, MAX_SEQ_LEN);
            long[] idsTrunc = truncate(ids, len);
            long[] maskTrunc = truncate(mask, len);
            long[] typeTrunc = truncate(typeIds, len);

            long[] shape = { 1, len };

            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            try (OnnxTensor idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(idsTrunc), shape);
                    OnnxTensor maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(maskTrunc), shape);
                    OnnxTensor typeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(typeTrunc), shape)) {

                inputs.put(inputIdsName, idTensor);
                inputs.put(attentionMaskName, maskTensor);
                if (tokenTypeIdsName != null) {
                    inputs.put(tokenTypeIdsName, typeTensor);
                }

                try (OrtSession.Result result = session.run(inputs)) {
                    return extractEmbedding(result, maskTrunc, len);
                }
            }
        } catch (Exception e) {
            log.error("Embedding failed for text of length {}: {}", text.length(), e.getMessage());
            throw new EmbeddingUnavailable("Embedding failed: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // Model loading
    // ----------------------------------------------------------------

    private synchronized void ensureLoaded() {
        if (available)
            return;

        log.info("Loading embedding model from {}", provisioner.modelDir());

        if (!provisioner.ensureModelFiles()) {
            unavailableReason = "Model files unavailable (download failed or offline)";
            log.warn("Embedding service unavailable: {}", unavailableReason);
            return;
        }

        try {
            Path modelPath = provisioner.path("model.onnx");
            Path tokenizerPath = provisioner.path("tokenizer.json");

            if (!Files.exists(modelPath)) {
                unavailableReason = "model.onnx missing";
                return;
            }
            if (!Files.exists(tokenizerPath)) {
                unavailableReason = "tokenizer.json missing";
                return;
            }

            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            this.session = env.createSession(modelPath.toString(), opts);

            this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

            // Resolve input/output names dynamically
            session.getInputNames().forEach(name -> log.debug("ONNX input: {}", name));
            session.getOutputNames().forEach(name -> log.debug("ONNX output: {}", name));

            this.inputIdsName = findFirst(session.getInputNames(), "input_ids", "inputIds");
            this.attentionMaskName = findFirst(session.getInputNames(), "attention_mask", "attentionMask");
            this.tokenTypeIdsName = findFirstOrNull(session.getInputNames(),
                    "token_type_ids", "tokenTypeIds");
            this.outputName = findFirst(session.getOutputNames(),
                    "last_hidden_state", "sentence_embedding", "token_embeddings");

            if (inputIdsName == null || attentionMaskName == null || outputName == null) {
                unavailableReason = "Unexpected ONNX model I/O names";
                log.error("{} — inputs={}, outputs={}",
                        unavailableReason, session.getInputNames(), session.getOutputNames());
                return;
            }

            this.available = true;
            log.info("Embedding model loaded. Inputs: ids={}, mask={}, types={}; Output: {}",
                    inputIdsName, attentionMaskName, tokenTypeIdsName, outputName);
        } catch (Throwable t) {
            unavailableReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.error("Embedding model load failed: {}", unavailableReason, t);
        }
    }

    // ----------------------------------------------------------------
    // Output extraction
    // ----------------------------------------------------------------

    private float[] extractEmbedding(OrtSession.Result result, long[] mask, int len) throws Exception {
        Object raw = result.get(outputName)
                .orElseThrow(() -> new EmbeddingUnavailable("Output " + outputName + " missing"))
                .getValue();

        // Most models return float[1][seq][hidden]; some return float[1][hidden].
        if (raw instanceof float[][][] batched) {
            return meanPoolAndNormalize(batched[0], mask, len);
        }
        if (raw instanceof float[][] single) {
            return l2Normalize(single[0]);
        }
        throw new EmbeddingUnavailable("Unexpected output type: " + raw.getClass().getName());
    }

    private float[] meanPoolAndNormalize(float[][] tokenVectors, long[] mask, int len) {
        int dim = tokenVectors[0].length;
        float[] sum = new float[dim];
        long count = 0;

        for (int i = 0; i < len; i++) {
            if (mask[i] == 0)
                continue;
            float[] v = tokenVectors[i];
            for (int j = 0; j < dim; j++)
                sum[j] += v[j];
            count++;
        }

        if (count == 0)
            count = 1;
        for (int j = 0; j < dim; j++)
            sum[j] /= count;

        return l2Normalize(sum);
    }

    private float[] l2Normalize(float[] v) {
        double sumSq = 0.0;
        for (float x : v)
            sumSq += x * x;
        double norm = Math.sqrt(sumSq);
        if (norm < 1e-12)
            norm = 1.0;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++)
            out[i] = (float) (v[i] / norm);
        return out;
    }

    private long[] truncate(long[] src, int len) {
        if (src.length == len)
            return src;
        long[] out = new long[len];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, len));
        return out;
    }

    private String findFirst(Iterable<String> names, String... candidates) {
        String hit = findFirstOrNull(names, candidates);
        return hit;
    }

    private String findFirstOrNull(Iterable<String> names, String... candidates) {
        for (String candidate : candidates) {
            for (String name : names) {
                if (name.equals(candidate))
                    return name;
            }
        }
        var it = names.iterator();
        return it.hasNext() ? it.next() : null;
    }

    // ----------------------------------------------------------------
    // Typed exception so callers can degrade gracefully
    // ----------------------------------------------------------------

    public static class EmbeddingUnavailable extends RuntimeException {
        public EmbeddingUnavailable(String message) {
            super(message);
        }
    }
}