package com.relay.controller;

import com.relay.model.PipelineContext;
import com.relay.model.StoryRequest;
import com.relay.service.PipelineOrchestrator;
import com.relay.service.SseEmitterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/workflows")
@CrossOrigin(origins = "http://localhost:4200")
public class PipelineController {

    private final PipelineOrchestrator orchestrator;
    private final SseEmitterService sseService;
    private final Map<String, PipelineContext> registry = new ConcurrentHashMap<>();

    public PipelineController(PipelineOrchestrator orchestrator, SseEmitterService sseService) {
        this.orchestrator = orchestrator;
        this.sseService = sseService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, String>> analyze(@RequestBody StoryRequest request) {
        PipelineContext ctx = new PipelineContext();
        ctx.setRequest(request);
        ctx.addLog("Story received: " + request.getStoryDescription());

        registry.put(ctx.getPipelineId(), ctx);
        orchestrator.run(ctx);

        return ResponseEntity.accepted().body(Map.of("pipelineId", ctx.getPipelineId()));
    }

    @GetMapping("/{id}/stream")
    public SseEmitter stream(@PathVariable String id) {
        PipelineContext ctx = registry.get(id);
        if (ctx == null) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send(SseEmitter.event().name("error").data("Pipeline not found"));
            } catch (Exception ignored) {
            }
            emitter.complete();
            return emitter;
        }
        return sseService.register(id, ctx);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<String> approve(@PathVariable String id,
            @RequestBody Map<String, Object> body) {
        PipelineContext ctx = registry.get(id);
        if (ctx == null)
            return ResponseEntity.notFound().build();

        String token = (String) body.get("checkpointToken");
        Boolean approved = (Boolean) body.get("approved");

        if (!ctx.getCheckpointToken().equals(token)) {
            return ResponseEntity.badRequest().body("Invalid checkpoint token");
        }
        if (ctx.getApprovalFuture() != null) {
            ctx.getApprovalFuture().complete(approved);
        }
        return ResponseEntity.ok("Approved: " + approved);
    }

    @PostMapping("/{id}/eject")
    public ResponseEntity<String> eject(@PathVariable String id) {
        PipelineContext ctx = registry.get(id);
        if (ctx == null)
            return ResponseEntity.notFound().build();
        if (ctx.getApprovalFuture() != null && !ctx.getApprovalFuture().isDone()) {
            ctx.getApprovalFuture().complete(true);
        }
        return ResponseEntity.ok("Ejected");
    }

    @GetMapping("/{id}/state")
    public ResponseEntity<PipelineContext> state(@PathVariable String id) {
        PipelineContext ctx = registry.get(id);
        if (ctx == null)
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(ctx);
    }
}