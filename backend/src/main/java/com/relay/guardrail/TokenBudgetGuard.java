package com.relay.guardrail;

import org.springframework.stereotype.Component;

@Component
public class TokenBudgetGuard {

    private static final long MAX_RUN_INPUT = 100_000L;
    private static final long MAX_RUN_OUTPUT = 50_000L;
    private static final double MONTHLY_CREDIT_LIMIT = 1500.0;

    private long runInput = 0L;
    private long runOutput = 0L;
    private long monthlyInput = 0L;
    private long monthlyOutput = 0L;

    public synchronized void track(long input, long output) {
        this.runInput += input;
        this.runOutput += output;
        this.monthlyInput += input;
        this.monthlyOutput += output;

        if (this.runInput > MAX_RUN_INPUT || this.runOutput > MAX_RUN_OUTPUT) {
            throw new RuntimeException("Per-run budget exceeded: input="
                    + runInput + ", output=" + runOutput);
        }
        if (getUsedCredits() > MONTHLY_CREDIT_LIMIT) {
            throw new RuntimeException("Monthly credit budget exceeded: "
                    + getUsedCredits());
        }
    }

    public synchronized void resetRun() {
        this.runInput = 0L;
        this.runOutput = 0L;
    }

    public double getUsedCredits() {
        return (monthlyInput + monthlyOutput) / 500_000.0;
    }

    public double getRemainingCredits() {
        return Math.max(0, MONTHLY_CREDIT_LIMIT - getUsedCredits());
    }

    public long getRunInput() {
        return runInput;
    }

    public long getRunOutput() {
        return runOutput;
    }

    public long getMonthlyInput() {
        return monthlyInput;
    }

    public long getMonthlyOutput() {
        return monthlyOutput;
    }
}