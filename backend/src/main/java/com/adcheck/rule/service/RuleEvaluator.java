package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface RuleEvaluator {
    Set<String> ruleCodes();
    RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request);

    /**
     * 같은 Claim에 적용되는 여러 {@link Rule}을 한 번에 평가한다. 기본 구현은 {@link #evaluate}를
     * 규칙마다 그대로 호출하는 것뿐이라, 호출 비용이 없는 평가기(예: 정규식 기반
     * {@code CommonRuleEvaluator}, {@code LiteralRuleEvaluator})는 이 메서드를 오버라이드할
     * 필요가 없다 — 코드 변경 없이 그대로 써도 동작이 같다.
     *
     * <p>AI 호출처럼 호출 1번의 비용이 큰 평가기(예: {@code AiRuleEvaluator})는 이 메서드를
     * 오버라이드해서 여러 {@code rules}를 한 프롬프트로 묶어 호출 횟수를 줄이는 용도로 쓴다.
     * 반환하는 맵의 키는 {@link Rule#getRuleCode()}이며, 입력 {@code rules} 전체에 대한 결과를
     * 빠짐없이 담아야 한다.
     */
    default Map<String, RuleEvaluation> evaluateBatch(List<Rule> rules, RuleAnalysisRequest request) {
        Map<String, RuleEvaluation> results = new LinkedHashMap<>();
        for (Rule rule : rules) {
            results.put(rule.getRuleCode(), evaluate(rule, request));
        }
        return results;
    }
}
