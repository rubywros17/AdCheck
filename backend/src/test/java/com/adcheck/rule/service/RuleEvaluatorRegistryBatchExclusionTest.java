package com.adcheck.rule.service;

import com.adcheck.rule.config.RuleJudgeProperties;
import com.adcheck.rule.domain.Rule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_COPY;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.CONDITION_NOT_MET;
import static com.adcheck.rule.service.RuleEvaluation.Status.NOT_MATCHED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 축 배치는 규칙 하나당 호출 1회로 끝나지만, 같은 프롬프트 안의 다른 Claim이 판단에 영향을
 * 줘서 개별 호출과 판정이 갈리는 규칙이 실측에서 확인됐다(B02_VIRUS·R02_MENOPAUSE·
 * O02_ENERGY_EXPANSION·L01_VISION — 2회 더 돌려도 같은 방향으로 틀림). 그 규칙만 예전처럼
 * Claim마다 개별 호출하도록 하는 게 {@code batchExcludedRuleCodes}다.
 *
 * <p>호출이 1회에서 Claim 수만큼 늘어나는 절충이라, <b>목록에 있는 규칙만</b> 그 비용을 치르고
 * 나머지는 배치 그대로여야 한다. 실수로 범위가 넓어지면 무료 티어 분당 한도(15회)를 그대로
 * 넘기므로 여기서 잠가둔다.
 */
class RuleEvaluatorRegistryBatchExclusionTest {

    private static final String EXCLUDED = "B02_VIRUS";
    private static final String BATCHED = "C27_FUNCTION_SYNERGY";

    @Test
    void 제외_목록에_있는_규칙은_Claim마다_개별로_평가한다() {
        RecordingEvaluator evaluator = new RecordingEvaluator(Set.of(EXCLUDED, BATCHED));
        RuleEvaluatorRegistry registry = registry(evaluator, List.of(EXCLUDED));

        List<RuleEvaluation> results =
                registry.evaluateAcrossClaims(CanonicalRuleFixture.rule(EXCLUDED), requests(3));

        assertThat(evaluator.singleCalls.get()).isEqualTo(3);
        assertThat(evaluator.batchCalls.get()).isZero();
        assertThat(results).hasSize(3);
    }

    @Test
    void 제외_목록에_없는_규칙은_배치로_한_번만_평가한다() {
        RecordingEvaluator evaluator = new RecordingEvaluator(Set.of(EXCLUDED, BATCHED));
        RuleEvaluatorRegistry registry = registry(evaluator, List.of(EXCLUDED));

        List<RuleEvaluation> results =
                registry.evaluateAcrossClaims(CanonicalRuleFixture.rule(BATCHED), requests(3));

        // 제외는 지목된 규칙에만 적용돼야 한다 — 넓어지면 호출 수가 그대로 분당 한도를 넘긴다.
        assertThat(evaluator.batchCalls.get()).isEqualTo(1);
        assertThat(evaluator.singleCalls.get()).isZero();
        assertThat(results).hasSize(3);
    }

    @Test
    void 제외_목록이_비어_있으면_모든_규칙이_배치로_간다() {
        RecordingEvaluator evaluator = new RecordingEvaluator(Set.of(EXCLUDED, BATCHED));
        RuleEvaluatorRegistry registry = registry(evaluator, List.of());

        registry.evaluateAcrossClaims(CanonicalRuleFixture.rule(EXCLUDED), requests(2));

        assertThat(evaluator.batchCalls.get()).isEqualTo(1);
        assertThat(evaluator.singleCalls.get()).isZero();
    }

    @Test
    void 개별_평가로_돌아도_결과는_입력_Claim의_순서와_크기를_지킨다() {
        RecordingEvaluator evaluator = new RecordingEvaluator(Set.of(EXCLUDED));
        RuleEvaluatorRegistry registry = registry(evaluator, List.of(EXCLUDED));

        List<RuleEvaluation> results =
                registry.evaluateAcrossClaims(CanonicalRuleFixture.rule(EXCLUDED), requests(4));

        // 개별 호출 경로도 배치와 계약이 같아야 한다 — 어긋나면 판정이 엉뚱한 문장에 붙는다.
        assertThat(results).extracting(RuleEvaluation::reason)
                .containsExactly("claim-1", "claim-2", "claim-3", "claim-4");
    }

    @Test
    void 평가기가_없는_규칙은_제외_목록과_무관하게_UNSUPPORTED_RULE이다() {
        RecordingEvaluator evaluator = new RecordingEvaluator(Set.of(BATCHED));
        RuleEvaluatorRegistry registry = registry(evaluator, List.of(EXCLUDED));

        List<RuleEvaluation> results =
                registry.evaluateAcrossClaims(CanonicalRuleFixture.rule(EXCLUDED), requests(2));

        assertThat(results).allSatisfy(result ->
                assertThat(result.reasonCode()).isEqualTo(RuleEvaluation.ReasonCode.UNSUPPORTED_RULE));
        assertThat(evaluator.singleCalls.get()).isZero();
        assertThat(evaluator.batchCalls.get()).isZero();
    }

    private static RuleEvaluatorRegistry registry(RuleEvaluator evaluator, List<String> excluded) {
        RuleJudgeProperties properties = new RuleJudgeProperties();
        properties.setEnabledRuleCodes(List.of(EXCLUDED, BATCHED));
        properties.setBatchExcludedRuleCodes(excluded);
        return new RuleEvaluatorRegistry(List.of(evaluator), properties);
    }

    private static List<RuleAnalysisRequest> requests(int count) {
        List<RuleAnalysisRequest> requests = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            requests.add(new RuleAnalysisRequest(
                    new Claim("claim-" + i, i + "번 문장", PRODUCT_COPY, "page-1#copy: 주변 문맥 확인됨"),
                    List.of(), Set.of(), null));
        }
        return List.copyOf(requests);
    }

    /** 어느 축으로 불렸는지 세는 대역. 판정 근거에 claimId를 실어 정렬도 검증할 수 있게 한다. */
    private static final class RecordingEvaluator implements RuleEvaluator {
        private final Set<String> ruleCodes;
        final AtomicInteger singleCalls = new AtomicInteger();
        final AtomicInteger batchCalls = new AtomicInteger();

        RecordingEvaluator(Set<String> ruleCodes) {
            this.ruleCodes = ruleCodes;
        }

        @Override
        public Set<String> ruleCodes() {
            return ruleCodes;
        }

        @Override
        public RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request) {
            singleCalls.incrementAndGet();
            return new RuleEvaluation(NOT_MATCHED, CONDITION_NOT_MET, request.claim().id());
        }

        @Override
        public List<RuleEvaluation> evaluateAcrossClaims(Rule rule, List<RuleAnalysisRequest> requests) {
            batchCalls.incrementAndGet();
            return requests.stream()
                    .map(request -> new RuleEvaluation(NOT_MATCHED, CONDITION_NOT_MET, request.claim().id()))
                    .toList();
        }
    }
}
