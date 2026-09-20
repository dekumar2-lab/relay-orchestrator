package com.relay.service;

import com.relay.agent.RelayOrchestratorAgent;
import com.relay.guardrail.SelfHealingGuardrail;
import com.relay.guardrail.TokenBudgetGuard;
import com.relay.model.*;
import com.relay.tools.DevOpsTool;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class PipelineOrchestrator {

    private final SseEmitterService sse;
    private final IntentExtractor intentExtractor;
    private final CodebaseLocator locator;
    private final ComplexityClassifier classifier;
    private final TokenBudgetGuard budgetGuard;
    private final SelfHealingGuardrail healingGuardrail;
    private final DevOpsTool devOpsTool;
    private final RelayOrchestratorAgent agent;

    public PipelineOrchestrator(SseEmitterService sse,
            IntentExtractor intentExtractor,
            CodebaseLocator locator,
            ComplexityClassifier classifier,
            TokenBudgetGuard budgetGuard,
            SelfHealingGuardrail healingGuardrail,
            DevOpsTool devOpsTool,
            RelayOrchestratorAgent agent) {
        this.sse = sse;
        this.intentExtractor = intentExtractor;
        this.locator = locator;
        this.classifier = classifier;
        this.budgetGuard = budgetGuard;
        this.healingGuardrail = healingGuardrail;
        this.devOpsTool = devOpsTool;
        this.agent = agent;
    }

    @Async
    public void run(PipelineContext ctx) {
        try {
            // Reset per-run state
            budgetGuard.resetRun();
            healingGuardrail.reset();

            String id = ctx.getPipelineId();
            String story = ctx.getRequest().getStoryDescription();

            log(ctx, "🚀 Pipeline started for story: " + truncate(story, 80));

            // ============================================================
            // PHASE 1: CLARIFY
            // ============================================================
            phase(ctx, PipelineStage.CLARIFY);
            log(ctx, "📋 Clarifier: Analyzing story...");

            IntentTokens intent = intentExtractor.extract(story);
            log(ctx, "📋 Clarifier: Project=" + intent.getProject()
                    + ", Target=" + intent.getTargetClass()
                    + ", Constraints=" + intent.getConstraints());

            // Verify LLM connectivity with a real call
            log(ctx, "🔍 Clarifier: Verifying LLM connectivity...");
            try {
                RelayOrchestratorAgent.LlmResult ping = agent.execute(id, "Reply with exactly: LLM_ALIVE");
                log(ctx, "🤖 LLM alive: " + ping.content()
                        + " (" + ping.inputTokens() + " in / "
                        + ping.outputTokens() + " out / "
                        + ping.elapsedMs() + "ms)");
                budgetGuard.track(ping.inputTokens(), ping.outputTokens());
                emitBudget(ctx);
            } catch (Exception e) {
                log(ctx, "⚠️ LLM probe failed: " + e.getMessage());
            }

            // ============================================================
            // PHASE 2: ARCHITECT
            // ============================================================
            phase(ctx, PipelineStage.ARCHITECT);
            log(ctx, "🏗️ Architect: Searching codebase...");

            List<FileCandidate> files = locator.locate(intent);
            ctx.setAffectedFiles(files);
            ComplexityLevel complexity = classifier.classify(files);
            ctx.setComplexity(complexity);

            ExecutionMode effective = recommendMode(complexity, intent);
            ctx.setEffectiveMode(effective);

            log(ctx, "🏗️ Architect: Found " + files.size() + " file(s). Complexity="
                    + complexity + ". Mode=" + effective);

            // Emit blast-radius payload to UI
            sse.emit(id, new SseEvent("phase_update", "ARCHITECT",
                    Map.of(
                            "files", files,
                            "complexity", complexity.name(),
                            "mode", effective.name())));

            // Human-in-the-loop checkpoint (Design Approval)
            if (effective == ExecutionMode.FULL_PIPELINE
                    || effective == ExecutionMode.PLAN_ONLY) {
                String artifact = buildDesignMarkdown(story, files, complexity, effective);
                boolean approved = waitForApproval(ctx, "Design Approval Required", artifact);
                if (!approved) {
                    fail(ctx, "Design rejected by user");
                    return;
                }
            }

            if (effective == ExecutionMode.PLAN_ONLY) {
                log(ctx, "🏗️ Architect: PLAN_ONLY mode — stopping after design.");
                complete(ctx);
                return;
            }

            // ============================================================
            // PHASE 3: DEVELOP
            // ============================================================
            phase(ctx, PipelineStage.DEVELOP);
            log(ctx, "⚙️ Developer: Applying code changes...");

            if (files.isEmpty()) {
                log(ctx, "⚠️ Developer: No target files discovered. Skipping write.");
            } else {
                for (FileCandidate fc : files) {
                    // Ask the LLM to generate the code change
                    String prompt = "Story: " + story
                            + "\n\nModify the file: " + fc.getFilePath()
                            + "\n\nReturn ONLY the modified file content. No explanations.";

                    RelayOrchestratorAgent.LlmResult llmResult = agent.execute(id, prompt);
                    budgetGuard.track(llmResult.inputTokens(), llmResult.outputTokens());
                    emitBudget(ctx);

                    // Write the LLM-generated content to the file
                    String writeResult = devOpsTool.writeFile(fc.getFilePath(),
                            llmResult.content());
                    log(ctx, "⚙️ Developer: " + writeResult
                            + " (" + llmResult.outputTokens() + " out tokens)");
                }
            }

            // ============================================================
            // PHASE 4: QA ENFORCER (with self-healing)
            // ============================================================
            phase(ctx, PipelineStage.QA);
            log(ctx, "🧪 QA: Running build...");

            int retries = 0;
            String buildResult = "FAIL: not attempted";
            boolean passed = false;

            while (retries < 3) {
                buildResult = devOpsTool.runBuild(".");
                if (buildResult.startsWith("PASS")) {
                    log(ctx, "✅ QA: Build passed.");
                    passed = true;
                    break;
                }

                log(ctx, "❌ QA: Build failed (retry " + (retries + 1) + "/3)");
                log(ctx, "🧪 QA: " + truncate(buildResult, 200));

                if (healingGuardrail.isLoop(buildResult)) {
                    log(ctx, "🚨 QA: Loop detected — same error repeated. Aborting retries.");
                    break;
                }

                // Ask the LLM to fix the build error
                if (!ctx.getAffectedFiles().isEmpty()) {
                    String fixPrompt = "The build failed. Fix the code.\n"
                            + "Error:\n" + buildResult + "\n"
                            + "File: " + ctx.getAffectedFiles().get(0).getFilePath()
                            + "\n\nReturn ONLY the corrected file content.";

                    RelayOrchestratorAgent.LlmResult fix = agent.execute(id, fixPrompt);
                    budgetGuard.track(fix.inputTokens(), fix.outputTokens());
                    emitBudget(ctx);

                    devOpsTool.writeFile(ctx.getAffectedFiles().get(0).getFilePath(),
                            fix.content());
                    log(ctx, "🔄 QA: Applied LLM fix attempt " + (retries + 1));
                }

                retries++;
                ctx.setRetryCount(retries);
            }

            if (!passed) {
                // Strict mode: abort pipeline
                boolean strict = Boolean.parseBoolean(
                        System.getProperty("relay.qa.strict", "false"));

                if (strict) {
                    log(ctx, "🚨 QA: Build failed after " + retries + " retries. Aborting.");
                    fail(ctx, "QA failed: " + truncate(buildResult, 200));
                    return;
                } else {
                    log(ctx, "⚠️ QA: Proceeding with failed build (non-strict mode).");
                    ctx.getArtifacts().put("qa_status", "DEGRADED");
                }
            }

            // Human-in-the-loop checkpoint (QA Approval) for FULL_PIPELINE only
            if (effective == ExecutionMode.FULL_PIPELINE) {
                String qaReport = "Build result: " + (passed ? "PASS" : "FAIL")
                        + "\nRetries: " + retries
                        + "\nDetails: " + truncate(buildResult, 500);

                boolean approved = waitForApproval(ctx, "QA Report — Approve to Create PR", qaReport);
                if (!approved) {
                    fail(ctx, "QA report rejected by user");
                    return;
                }
            }

            // ============================================================
            // PHASE 5: HANDOFF
            // ============================================================
            phase(ctx, PipelineStage.HANDOFF);
            log(ctx, "📨 Handoff: Creating PR...");

            String branchName = "relay/" + UUID.randomUUID().toString().substring(0, 8);
            String prUrl = devOpsTool.createPR(branchName,
                    "Automated change: " + truncate(story, 60));

            String handoff = buildHandoffMarkdown(story, files, complexity, effective,
                    retries, passed, prUrl);
            ctx.getArtifacts().put("handoff.md", handoff);

            log(ctx, "📨 Handoff: PR created → " + prUrl);
            log(ctx, "📨 Handoff: Total retries: " + retries
                    + ", QA status: " + (passed ? "PASS" : "DEGRADED"));

            complete(ctx);

        } catch (Exception e) {
            e.printStackTrace();
            log(ctx, "🚨 Pipeline error: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            fail(ctx, e.getMessage());
        }
    }

    // ================================================================
    // Helper methods
    // ================================================================

    private ExecutionMode recommendMode(ComplexityLevel complexity, IntentTokens intent) {
        Object planOnly = intent.getConstraints().get("planOnly");
        if (Boolean.TRUE.equals(planOnly))
            return ExecutionMode.PLAN_ONLY;

        Object skipTests = intent.getConstraints().get("skipTests");

        if (complexity == ComplexityLevel.SMALL)
            return ExecutionMode.CODE_ONLY;
        if (complexity == ComplexityLevel.MEDIUM && Boolean.TRUE.equals(skipTests))
            return ExecutionMode.CODE_ONLY;
        return ExecutionMode.FULL_PIPELINE;
    }

    private boolean waitForApproval(PipelineContext ctx, String title, String artifact)
            throws Exception {
        String token = UUID.randomUUID().toString();
        ctx.setCheckpointToken(token);
        ctx.setAwaitingApproval(true);
        ctx.setApprovalFuture(new CompletableFuture<>());

        log(ctx, "⏸️ Awaiting user approval: " + title);
        sse.emit(ctx.getPipelineId(), new SseEvent("checkpoint", title,
                Map.of("token", token, "artifact", artifact)));

        Boolean approved = ctx.getApprovalFuture().get(300, TimeUnit.SECONDS);
        ctx.setAwaitingApproval(false);
        return Boolean.TRUE.equals(approved);
    }

    private void phase(PipelineContext ctx, PipelineStage stage) {
        ctx.setStage(stage);
        sse.emit(ctx.getPipelineId(), new SseEvent("phase_update", stage.name(), null));
    }

    private void log(PipelineContext ctx, String message) {
        ctx.addLog(message);
        sse.emit(ctx.getPipelineId(), new SseEvent("log", message, null));
    }

    private void emitBudget(PipelineContext ctx) {
        ctx.setInputTokens(budgetGuard.getRunInput());
        ctx.setOutputTokens(budgetGuard.getRunOutput());

        sse.emit(ctx.getPipelineId(), new SseEvent("budget_update", "budget",
                Map.of(
                        "remaining", budgetGuard.getRemainingCredits(),
                        "inputTokens", budgetGuard.getRunInput(),
                        "outputTokens", budgetGuard.getRunOutput(),
                        "monthlyInput", budgetGuard.getMonthlyInput(),
                        "monthlyOutput", budgetGuard.getMonthlyOutput(),
                        "usedCredits", budgetGuard.getUsedCredits())));
    }

    private void complete(PipelineContext ctx) {
        ctx.setStage(PipelineStage.COMPLETED);
        log(ctx, "✅ Pipeline completed successfully.");
        sse.emit(ctx.getPipelineId(),
                new SseEvent("complete", "Pipeline completed", null));
        sse.complete(ctx.getPipelineId());
    }

    private void fail(PipelineContext ctx, String reason) {
        ctx.setStage(PipelineStage.FAILED);
        log(ctx, "🚨 Pipeline failed: " + reason);
        sse.emit(ctx.getPipelineId(), new SseEvent("error", reason, null));
        sse.complete(ctx.getPipelineId());
    }

    private String buildDesignMarkdown(String story, List<FileCandidate> files,
            ComplexityLevel complexity, ExecutionMode mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Technical Design\n\n");
        sb.append("**Story:** ").append(story).append("\n\n");
        sb.append("**Complexity:** ").append(complexity).append("\n\n");
        sb.append("**Execution Mode:** ").append(mode).append("\n\n");
        sb.append("## Files to Modify\n\n");
        if (files.isEmpty()) {
            sb.append("_No files discovered._\n");
        } else {
            for (FileCandidate fc : files) {
                sb.append("- `").append(fc.getFilePath()).append("` (")
                        .append(String.format("%.0f%%", fc.getConfidence() * 100))
                        .append(" confidence, ").append(fc.getReason()).append(")\n");
            }
        }
        return sb.toString();
    }

    private String buildHandoffMarkdown(String story, List<FileCandidate> files,
            ComplexityLevel complexity, ExecutionMode mode,
            int retries, boolean passed, String prUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Handoff Report\n\n");
        sb.append("**Story:** ").append(story).append("\n\n");
        sb.append("**Complexity:** ").append(complexity).append("\n\n");
        sb.append("**Execution Mode:** ").append(mode).append("\n\n");
        sb.append("**QA Status:** ").append(passed ? "PASS" : "DEGRADED").append("\n\n");
        sb.append("**Retries:** ").append(retries).append("\n\n");
        sb.append("**Pull Request:** ").append(prUrl).append("\n\n");
        sb.append("## Files Changed\n\n");
        for (FileCandidate fc : files) {
            sb.append("- `").append(fc.getFilePath()).append("`\n");
        }
        sb.append("\n## Rollback\n\n");
        sb.append("If this change causes issues, revert the PR or run:\n");
        sb.append("```\ngit revert HEAD~1\n```\n");
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null)
            return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}