package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.UNSUPPORTED_RULE;

@Component
public class RuleEvaluatorRegistry {
    private final Map<String, RuleEvaluator> evaluators;

    public RuleEvaluatorRegistry(List<RuleEvaluator> evaluators) {
        Map<String, RuleEvaluator> registrations = new HashMap<>();
        for (RuleEvaluator evaluator : evaluators) {
            for (String code : evaluator.ruleCodes()) {
                if (registrations.putIfAbsent(code, evaluator) != null) {
                    throw new IllegalStateException("Duplicate evaluator for " + code);
                }
            }
        }
        this.evaluators = Map.copyOf(registrations);
    }

    public RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request) {
        RuleEvaluator evaluator = evaluators.get(rule.getRuleCode());
        return evaluator == null
                ? new RuleEvaluation(REVIEW_REQUIRED, UNSUPPORTED_RULE, "등록된 자동 평가기가 없습니다.")
                : evaluator.evaluate(rule, request);
    }
}
