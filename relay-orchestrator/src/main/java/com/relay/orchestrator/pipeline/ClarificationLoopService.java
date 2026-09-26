package com.relay.orchestrator.pipeline;

import com.relay.orchestrator.agent.AgentPersona;
import com.relay.orchestrator.agent.AgentRegistry;
import com.relay.orchestrator.agent.AgentRole;
import com.relay.orchestrator.connection.ConnectionConfig;
import com.relay.orchestrator.connection.ConnectionConfigService;
import com.relay.orchestrator.logging.LogBroadcaster;
import com.relay.orchestrator.logging.LogEvent;
import com.relay.orchestrator.service.ClarificationResult;
import com.relay.orchestrator.service.ClarifyingQuestion;
import com.relay.orchestrator.service.IntentClarifierService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The clarification loop orchestrator.
 *
 * Handles:
 * - Session creation from a fresh story
 * - Turn-by-turn resumption with user answers
 * - Hard cap enforcement (max 3 turns)
 * - Stage transitions
 *
 * Structure is LangGraph4j-compatible: each method here becomes a node
 * in the eventual graph, and PipelineSession becomes the graph state.
 */
@Service
public class ClarificationLoopService {

    private static final Logger log = LoggerFactory.getLogger(ClarificationLoopService.class);

    private static final int DEFAULT_MAX_TURNS = 3;

    private final IntentClarifierService clarifier;
    private final PipelineSessionStore sessionStore;
    private final ConnectionConfigService configService;
    private final AgentRegistry agentRegistry;
    private final LogBroadcaster logBroadcaster;

    public ClarificationLoopService(IntentClarifierService clarifier,
            PipelineSessionStore sessionStore,
            ConnectionConfigService configService,
            AgentRegistry agentRegistry,
            LogBroadcaster logBroadcaster) {
        this.clarifier = clarifier;
        this.sessionStore = sessionStore;
        this.configService = configService;
        this.agentRegistry = agentRegistry;
        this.logBroadcaster = logBroadcaster;
    }

    // ----------------------------------------------------------------
    // Entry point: start a new session from a story
    // ----------------------------------------------------------------

    public PipelineSession start(String story) {
        if (story == null || story.isBlank()) {
            throw new IllegalArgumentException("Story is required");
        }

        String sessionId = UUID.randomUUID().toString();
        PipelineSession session = PipelineSession.fresh(sessionId, story, DEFAULT_MAX_TURNS);

        logBroadcaster.publish(LogEvent.info(
                "[LOOP] Starting session " + shortId(sessionId)));

        return runClarifierTurn(session, List.of());
    }

    // ----------------------------------------------------------------
    // Resume: user has provided answers to previous questions
    // ----------------------------------------------------------------

    public PipelineSession resume(String sessionId, List<AnsweredQuestion> newAnswers,
            String additionalContext) {
        PipelineSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (!session.stage().isAwaitingUser()) {
            throw new IllegalStateException(
                    "Session " + shortId(sessionId) + " is in stage "
                            + session.stage() + " — cannot accept answers");
        }

        // Merge new answers into history
        List<AnsweredQuestion> mergedHistory = new ArrayList<>(session.qaHistory());
        if (newAnswers != null) {
            for (AnsweredQuestion a : newAnswers) {
                String questionText = findOriginalQuestion(session, a.questionId());
                mergedHistory.add(new AnsweredQuestion(
                        a.questionId(), questionText, a.why(), a.answer()));
            }
        }

        if (additionalContext != null && !additionalContext.isBlank()) {
            mergedHistory.add(new AnsweredQuestion(
                    "note", "Additional context from user", "", additionalContext));
        }

        logBroadcaster.publish(LogEvent.info(
                "[LOOP] Resuming session " + shortId(sessionId)
                        + " (turn " + (session.turnCount() + 1) + "/" + session.maxTurns() + ")"));

        // Reconstruct with merged history. The record now has 10 components
        // (implementationResult sits between lastResult and createdAt).
        PipelineSession withHistory = new PipelineSession(
                session.id(),
                session.originalStory(),
                session.stage(),
                session.turnCount(),
                session.maxTurns(),
                mergedHistory,
                session.lastResult(),
                session.implementationResult(),
                session.createdAt(),
                java.time.LocalDateTime.now());

        return runClarifierTurn(withHistory, mergedHistory);
    }

    // ----------------------------------------------------------------
    // Core turn execution
    // ----------------------------------------------------------------

    private PipelineSession runClarifierTurn(PipelineSession session,
            List<AnsweredQuestion> history) {

        PipelineSession incremented = session.incrementTurn();

        if (incremented.turnCount() > incremented.maxTurns()) {
            logBroadcaster.publish(LogEvent.warn(
                    "[LOOP] Session " + shortId(session.id()) + " exceeded turn cap"));
            PipelineSession capped = incremented.with(PipelineStage.CAPPED, incremented.lastResult());
            sessionStore.save(capped);
            return capped;
        }

        boolean isLastChance = incremented.turnCount() >= incremented.maxTurns();
        if (isLastChance) {
            logBroadcaster.publish(LogEvent.warn(
                    "[LOOP] Session " + shortId(session.id())
                            + " on final turn (" + incremented.turnCount()
                            + "/" + incremented.maxTurns() + ")"));
        }

        ClarificationResult result = clarifier.clarifyWithHistory(
                incremented.originalStory(),
                incremented.id(),
                history,
                isLastChance);

        PipelineStage nextStage = stageFromResult(result);
        PipelineSession updated = incremented.with(nextStage, result);
        sessionStore.save(updated);

        logBroadcaster.publish(LogEvent.info(
                "[LOOP] Session " + shortId(session.id())
                        + " → stage=" + nextStage
                        + " state=" + result.state()
                        + " turn=" + updated.turnCount() + "/" + updated.maxTurns()));

        return updated;
    }

    private PipelineStage stageFromResult(ClarificationResult result) {
        return switch (result.state()) {
            case READY -> PipelineStage.READY_TO_IMPLEMENT;
            case NEEDS_INPUT -> PipelineStage.AWAITING_ANSWERS;
            case BLOCKED -> PipelineStage.BLOCKED;
        };
    }

    private String findOriginalQuestion(PipelineSession session, String questionId) {
        if (session.lastResult() == null || session.lastResult().questions() == null) {
            return "";
        }
        for (ClarifyingQuestion q : session.lastResult().questions()) {
            if (q.id().equals(questionId)) {
                return q.question();
            }
        }
        return "";
    }

    private String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    // ----------------------------------------------------------------
    // Read access
    // ----------------------------------------------------------------

    public PipelineSession get(String sessionId) {
        return sessionStore.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    public List<PipelineSession> recent(int limit) {
        return sessionStore.recent(limit);
    }

    // ----------------------------------------------------------------
    // Write access — used by the implementer to persist its result
    // ----------------------------------------------------------------

    /**
     * Persist a session directly. Called by ConnectionController after the
     * implementer finishes, so the diff is available on the next page load.
     */
    public void save(PipelineSession session) {
        sessionStore.save(session);
    }
}