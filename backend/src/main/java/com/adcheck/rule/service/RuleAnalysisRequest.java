package com.adcheck.rule.service;

import com.adcheck.rule.model.RuleOfficialFunctionContext;
import com.adcheck.rule.model.RiskSignalContext;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One complete claim, with context established by the caller, not by risk candidates. */
public record RuleAnalysisRequest(
        Claim claim,
        List<RiskSignalContext> riskSignals,
        Set<Long> confirmedIngredientMasterIds,
        OfficialFunctions officialFunctions
) {
    public RuleAnalysisRequest {
        Objects.requireNonNull(claim, "claim");
        riskSignals = riskSignals == null ? List.of() : List.copyOf(riskSignals);
        confirmedIngredientMasterIds = confirmedIngredientMasterIds == null
                ? Set.of() : Set.copyOf(confirmedIngredientMasterIds);
        if (confirmedIngredientMasterIds.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("IngredientMaster IDs must be positive");
        }
        officialFunctions = officialFunctions == null ? new OfficialFunctions(List.of(), false, false)
                : officialFunctions;
    }

    public record Claim(String id, String text, Context context, String contextEvidence) {
        public Claim {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("claim.id is required");
            context = context == null ? Context.UNKNOWN : context;
        }
    }

    public enum Context {
        /** Caller checked the surrounding advertisement and this is the entire standalone product copy. */
        PRODUCT_COPY,
        /** Caller additionally verified that an otherwise unspecified effect means a health effect. */
        PRODUCT_HEALTH_EFFECT_COPY,
        /** Caller verified this is independent information, with no product effect attribution. */
        NON_PRODUCT_INFORMATION,
        UNKNOWN
    }

    public record OfficialFunctions(
            List<RuleOfficialFunctionContext> values,
            boolean productApplicabilityVerified,
            boolean allMainIngredientsCovered
    ) {
        public OfficialFunctions {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
