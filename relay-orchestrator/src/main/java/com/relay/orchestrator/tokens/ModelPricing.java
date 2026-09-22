package com.relay.orchestrator.tokens;

public record ModelPricing(
        Kind kind,
        double inputPerMillion,
        double outputPerMillion,
        double cacheWriteMultiplier,
        double cacheReadMultiplier) {

    public enum Kind {
        /** Billed per token: Anthropic, OpenAI direct, etc. */
        PER_TOKEN,
        /** Flat subscription: Copilot, where marginal cost per call is $0. */
        FLAT_RATE,
        /** Unknown: can't estimate, cost column shows "—". */
        UNKNOWN
    }

    public static ModelPricing perToken(double in, double out,
                                         double cacheWrite, double cacheRead) {
        return new ModelPricing(Kind.PER_TOKEN, in, out, cacheWrite, cacheRead);
    }

    public static ModelPricing flatRate() {
        return new ModelPricing(Kind.FLAT_RATE, 0, 0, 0, 0);
    }

    public static ModelPricing unknown() {
        return new ModelPricing(Kind.UNKNOWN, 0, 0, 0, 0);
    }

    public double computeCost(int freshInput, int cacheWrite, int cacheRead, int output) {
        return switch (kind) {
            case FLAT_RATE, UNKNOWN -> 0.0;
            case PER_TOKEN -> {
                double fresh = (freshInput / 1_000_000.0) * inputPerMillion;
                double cw = (cacheWrite / 1_000_000.0) * inputPerMillion * cacheWriteMultiplier;
                double cr = (cacheRead / 1_000_000.0) * inputPerMillion * cacheReadMultiplier;
                double out = (output / 1_000_000.0) * outputPerMillion;
                yield fresh + cw + cr + out;
            }
        };
    }

    public boolean isEstimable() {
        return kind == Kind.PER_TOKEN;
    }
}