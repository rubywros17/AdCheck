package com.adcheck.rule.service;

import com.adcheck.rule.config.RuleJudgeProperties;
import com.adcheck.rule.domain.Rule;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.UNSUPPORTED_RULE;

@Component
public class RuleEvaluatorRegistry {
    private static final Logger log = LoggerFactory.getLogger(RuleEvaluatorRegistry.class);

    private final Map<String, RuleEvaluator> evaluators;
    private final Set<String> batchExcludedRuleCodes;

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
        this.batchExcludedRuleCodes = properties == null
                ? Set.of() : Set.copyOf(properties.getBatchExcludedRuleCodes());
        if (!this.batchExcludedRuleCodes.isEmpty()) {
            log.info("배치 제외 규칙 {}개 — Claim마다 개별 호출합니다: {}",
                    this.batchExcludedRuleCodes.size(), this.batchExcludedRuleCodes);
        }
    }

    public RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request) {
        RuleEvaluator evaluator = evaluators.get(rule.getRuleCode());
        return evaluator == null
                ? unsupported()
                : evaluator.evaluate(rule, request);
    }

    /**
     * 같은 규칙을 여러 Claim에 한 번에 평가한다 — AI 평가기는 이걸 한 번의 호출로 묶어
     * 처리한다({@link RuleEvaluator#evaluateAcrossClaims}).
     */
    public List<RuleEvaluation> evaluateAcrossClaims(Rule rule, List<RuleAnalysisRequest> requests) {
        RuleEvaluator evaluator = evaluators.get(rule.getRuleCode());
        if (evaluator == null) {
            return Collections.nCopies(requests.size(), unsupported());
        }
        if (batchExcludedRuleCodes.contains(rule.getRuleCode())) {
            // 실측에서 배치와 개별 호출의 판정이 반복해서 갈린 규칙들이다
            // ({@code RuleJudgeProperties.batchExcludedRuleCodes} 참고). 호출이 1회에서 Claim
            // 수만큼 늘지만, 배치로 인한 오판정보다는 호출 비용을 택한다.
            return requests.stream().map(request -> evaluator.evaluate(rule, request)).toList();
        }
        return evaluator.evaluateAcrossClaims(rule, requests);
    }

    private static RuleEvaluation unsupported() {
        return new RuleEvaluation(REVIEW_REQUIRED, UNSUPPORTED_RULE, "등록된 자동 평가기가 없습니다.");
    }
}
