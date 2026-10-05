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

    /**
     * Tracks the input and output values, ensuring null values are treated as zero.
     * Updates the run and monthly budgets accordingly.
     * Throws RuntimeException if any budget limit is exceeded.
     *
     * @param input  the input value for the current operation (nullable)
     * @param output the output value for the current operation (nullable)
     */
    public synchronized void track(Long input, Long output) {
        long safeInput = (input == null) ? 0L : input;
        long safeOutput = (output == null) ? 0L : output;

        this.runInput += safeInput;
        this.runOutput += safeOutput;
        this.monthlyInput += safeInput;
        this.monthlyOutput += safeOutput;

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