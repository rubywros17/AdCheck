package com.adcheck.rule.model;

/** Extraction hint for this claim. Unknown types and original text are retained, never treated as verdicts. */
public record RiskSignalContext(String signalType, String text) {
    public RiskSignalContext {
        if (signalType == null || signalType.isBlank()) {
            throw new IllegalArgumentException("risk signalType is required");
        }
    }

    public boolean relatesTo(String judgmentCategory) {
        return signalType.equals(judgmentCategory)
                || (signalType.equals("TIME_GUARANTEE") && "RESULT_TIME_AMOUNT".equals(judgmentCategory));
    }
}
