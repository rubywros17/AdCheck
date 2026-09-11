package com.adcheck.rule.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.adcheck.rule.service.RuleAnalysisResult.Diagnostic;
import com.adcheck.rule.service.RuleAnalysisResult.RuleMatch;

@Service
public class RuleAnalysisService {
    private final RuleSelector selector;
    private final RuleEvaluatorRegistry registry;
    private final RuleSourceResolver sourceResolver;

    public RuleAnalysisService(RuleSelector selector, RuleEvaluatorRegistry registry, RuleSourceResolver sourceResolver) {
        this.selector = selector;
        this.registry = registry;
        this.sourceResolver = sourceResolver;
    }

    @Transactional(readOnly = true)
    public RuleAnalysisResult analyze(RuleAnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        var rules = selector.select(request.confirmedIngredientMasterIds());
        var sources = sourceResolver.resolve(rules.stream().map(r -> r.getId()).toList());
        var matches = rules.stream().map(rule -> {
            var resolved = sources.getOrDefault(rule.getId(), List.of());
            List<Diagnostic> diagnostics = new ArrayList<>();
            if (resolved.isEmpty()) diagnostics.add(Diagnostic.SOURCE_MISSING);
            if ("DRAFT".equals(rule.getReviewStatus())) diagnostics.add(Diagnostic.DRAFT_RULE);
            return new RuleMatch(request.claim().id(), rule.getId(), rule.getRuleCode(), rule.getRuleVersion(),
                    rule.getScopeType(), rule.getJudgmentCategory(), rule.getSeverity(), rule.getReviewStatus(),
                    request.riskSignalCandidates().contains(rule.getRuleCode()), registry.evaluate(rule, request),
                    resolved, diagnostics);
        }).toList();
        return new RuleAnalysisResult(request.claim().id(), matches,
                request.confirmedIngredientMasterIds().isEmpty()
                        ? List.of(Diagnostic.INGREDIENT_SPECIFIC_NOT_EVALUATED) : List.of());
    }
}
