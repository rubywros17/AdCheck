package com.adcheck.rule.service;

import com.adcheck.rule.config.RuleJudgeProperties;
import com.adcheck.rule.domain.Rule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.UNSUPPORTED_RULE;

@Component
public class RuleEvaluatorRegistry {
    private final Map<String, RuleEvaluator> evaluators;

    /** 테스트 편의용 — allowlist 없이 전달된 evaluator들의 규칙을 필터링 없이 전부 등록한다. */
    public RuleEvaluatorRegistry(List<RuleEvaluator> evaluators) {
        this(evaluators, null);
    }

    /**
     * Spring이 쓰는 생성자. {@code properties}가 있으면 그 allowlist에 있는 규칙 코드만
     * 등록하고(단계적 파일럿 확대를 코드 변경 없이 설정으로 하기 위함), 없으면(테스트에서
     * 위 생성자로 만든 경우) 필터링하지 않는다.
     */
    @Autowired
    public RuleEvaluatorRegistry(List<RuleEvaluator> evaluators, RuleJudgeProperties properties) {
        Set<String> allowlist = properties == null ? null : Set.copyOf(properties.getEnabledRuleCodes());
        Map<String, RuleEvaluator> registrations = new HashMap<>();
        for (RuleEvaluator evaluator : evaluators) {
            for (String code : evaluator.ruleCodes()) {
                if (allowlist != null && !allowlist.contains(code)) {
                    continue;
                }
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
