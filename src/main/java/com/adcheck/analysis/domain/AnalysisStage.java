package com.adcheck.analysis.domain;

public enum AnalysisStage {
    WAITING,
    STT,
    VISION,
    RULE_ANALYSIS,
    RAG_RETRIEVAL,
    LLM_ANALYSIS,
    GROUNDING_VERIFICATION,
    COMPLETED
}
