package com.relay.controller;

import com.relay.guardrail.TokenBudgetGuard;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/budget")
@CrossOrigin(origins = "http://localhost:4200")
public class BudgetController {

    private final TokenBudgetGuard guard;

    public BudgetController(TokenBudgetGuard guard) {
        this.guard = guard;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "remaining", guard.getRemainingCredits(),
                "totalLimit", 1500,
                "inputTokens", 1200,
                "outputTokens", 1244);
    }
}