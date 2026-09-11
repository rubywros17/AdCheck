package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import java.util.Set;

public interface RuleEvaluator {
    Set<String> ruleCodes();
    RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request);
}
