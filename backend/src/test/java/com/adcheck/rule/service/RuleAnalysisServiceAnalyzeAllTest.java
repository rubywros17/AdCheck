package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.repository.RuleIngredientRepository;
import com.adcheck.rule.repository.RuleRepository;
import com.adcheck.rule.repository.RuleSourceRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.adcheck.rule.service.RuleAnalysisRequest.Claim;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.PRODUCT_COPY;
import static com.adcheck.rule.service.RuleAnalysisResult.Diagnostic.INGREDIENT_SPECIFIC_NOT_EVALUATED;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.SEMANTIC_COMPARISON_REQUIRED;
import static com.adcheck.rule.service.RuleEvaluation.Status.MATCHED;
import static com.adcheck.rule.service.RuleEvaluation.Status.NOT_MATCHED;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 프로덕션이 실제로 쓰는 경로는 {@link RuleAnalysisService#analyzeAll}이다 — {@code FindingAssembler}는
 * Claim마다 {@code analyze()}를 부르지 않고 이것만 부른다. 그런데 {@link RuleAnalysisServiceTest}는
 * 전부 단일 Claim용 {@code analyze()}만 검증하고 있어서, 정작 도는 배치 경로에는 회귀 방어가
 * 하나도 없었다. 이 클래스가 그 구멍을 메운다.
 *
 * <p>특히 지키려는 성질은 세 가지다: <b>①호출 축</b>(규칙 하나당 평가 1회이고 Claim 수에 비례해
 * 늘지 않는다 — 무료 티어 분당 한도를 넘지 않는 근거), <b>②정렬</b>(결과가 입력 Claim 순서와
 * 1:1로 맞는다 — 어긋나면 엉뚱한 문장에 판정이 붙는다), <b>③실패 격리 단위</b>(규칙 하나가
 * 실패해도 Claim을 통째로 버리지 않는다 — 예전 구조에서 429가 났을 때 결과가 조용히 사라진 원인).
 */
class RuleAnalysisServiceAnalyzeAllTest {

    private static final String RULE_A = "C07_ABSOLUTE_EFFECT";
    private static final String RULE_B = "C24_OVERCONSUMPTION";

    private final RuleRepository rules = mock(RuleRepository.class);
    private final RuleIngredientRepository ingredients = mock(RuleIngredientRepository.class);
    private final RuleSourceRepository sources = mock(RuleSourceRepository.class);
    private final RuleSelector selector = new RuleSelector(rules, ingredients);

    @Test
    void 규칙_하나당_한_번만_평가하고_Claim_수에_비례해_늘지_않는다() {
        Rule ruleA = CanonicalRuleFixture.rule(RULE_A);
        Rule ruleB = CanonicalRuleFixture.rule(RULE_B);
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON")).thenReturn(List.of(ruleA, ruleB));
        ScriptedEvaluator evaluator = new ScriptedEvaluator(Map.of(
                RULE_A, always(NOT_MATCHED), RULE_B, always(NOT_MATCHED)));

        service(evaluator).analyzeAll(requests("첫 문장", "둘째 문장", "셋째 문장"));

        // Claim 3건 × 규칙 2개 = 6회가 아니라, 규칙 수인 2회여야 한다.
        assertThat(evaluator.batchCalls).containsExactlyInAnyOrder(RULE_A, RULE_B);
        assertThat(evaluator.batchSizes).containsExactly(3, 3);
        assertThat(evaluator.singleCalls.get()).isZero();
    }

    @Test
    void 결과는_입력_Claim의_순서와_1대1로_맞는다() {
        Rule ruleA = CanonicalRuleFixture.rule(RULE_A);
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON")).thenReturn(List.of(ruleA));
        // Claim마다 다른 판정을 돌려주는 대역 — 순서가 틀어지면 아래 단언이 깨진다.
        ScriptedEvaluator evaluator = new ScriptedEvaluator(Map.of(RULE_A, requests -> List.of(
                evaluation(MATCHED), evaluation(NOT_MATCHED), evaluation(REVIEW_REQUIRED))));

        List<RuleAnalysisResult> results = service(evaluator).analyzeAll(requests("가", "나", "다"));

        assertThat(results).extracting(RuleAnalysisResult::claimId)
                .containsExactly("claim-1", "claim-2", "claim-3");
        assertThat(results).extracting(r -> r.matches().getFirst().evaluation().status())
                .containsExactly(MATCHED, NOT_MATCHED, REVIEW_REQUIRED);
        assertThat(results).allSatisfy(result ->
                assertThat(result.matches()).allSatisfy(match ->
                        assertThat(match.claimId()).isEqualTo(result.claimId())));
    }

    @Test
    void 규칙_하나가_예외를_던져도_그_규칙만_확인_필요가_되고_나머지_규칙은_살아남는다() {
        Rule failing = CanonicalRuleFixture.rule(RULE_A);
        Rule healthy = CanonicalRuleFixture.rule(RULE_B);
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON")).thenReturn(List.of(failing, healthy));
        ScriptedEvaluator evaluator = new ScriptedEvaluator(Map.of(
                RULE_A, requests -> {
                    throw new IllegalStateException("429 재시도 후에도 실패");
                },
                RULE_B, always(MATCHED)));

        List<RuleAnalysisResult> results = service(evaluator).analyzeAll(requests("가", "나"));

        // 예전 구조는 여기서 Claim을 통째로 버렸다 — 그러면 사용자는 "문제 없음"으로 오해한다.
        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(result -> {
            var failed = matchOf(result, failing);
            assertThat(failed.evaluation().status()).isEqualTo(REVIEW_REQUIRED);
            assertThat(failed.evaluation().reasonCode()).isEqualTo(SEMANTIC_COMPARISON_REQUIRED);
            assertThat(matchOf(result, healthy).evaluation().status()).isEqualTo(MATCHED);
        });
    }

    @Test
    void 평가_결과_개수가_요청_수와_다르면_그_규칙은_확인_필요로_처리된다() {
        Rule ruleA = CanonicalRuleFixture.rule(RULE_A);
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON")).thenReturn(List.of(ruleA));
        // 3건을 보냈는데 2건만 돌아오는 상황 — 그대로 쓰면 판정이 한 칸씩 밀려 엉뚱한 문장에 붙는다.
        ScriptedEvaluator evaluator = new ScriptedEvaluator(Map.of(
                RULE_A, requests -> List.of(evaluation(MATCHED), evaluation(MATCHED))));

        List<RuleAnalysisResult> results = service(evaluator).analyzeAll(requests("가", "나", "다"));

        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(result ->
                assertThat(result.matches().getFirst().evaluation().status()).isEqualTo(REVIEW_REQUIRED));
    }

    @Test
    void 요청이_비어_있으면_규칙_조회조차_하지_않는다() {
        ScriptedEvaluator evaluator = new ScriptedEvaluator(Map.of(RULE_A, always(MATCHED)));

        assertThat(service(evaluator).analyzeAll(List.of())).isEmpty();

        verifyNoInteractions(rules, ingredients, sources);
        assertThat(evaluator.batchCalls).isEmpty();
    }

    @Test
    void 모든_Claim이_NOT_MATCHED인_규칙은_출처_조회_대상에서_빠진다() {
        Rule notMatched = CanonicalRuleFixture.rule(RULE_A);
        Rule matched = CanonicalRuleFixture.rule(RULE_B);
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON")).thenReturn(List.of(notMatched, matched));
        ScriptedEvaluator evaluator = new ScriptedEvaluator(Map.of(
                RULE_A, always(NOT_MATCHED), RULE_B, always(MATCHED)));

        service(evaluator).analyzeAll(requests("가", "나"));

        // Claim 단위가 아니라 규칙 단위로 판단해야 한다 — 한 Claim이라도 NOT_MATCHED가 아니면 포함.
        verify(sources).findAllWithSourceByRuleIds(List.of(matched.getId()));
    }

    @Test
    void 원료가_확정되지_않으면_모든_Claim에_같은_진단이_붙는다() {
        when(rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON"))
                .thenReturn(List.of(CanonicalRuleFixture.rule(RULE_A)));
        ScriptedEvaluator evaluator = new ScriptedEvaluator(Map.of(RULE_A, always(NOT_MATCHED)));

        List<RuleAnalysisResult> results = service(evaluator).analyzeAll(requests("가", "나"));

        assertThat(results).allSatisfy(result ->
                assertThat(result.diagnostics()).containsExactly(INGREDIENT_SPECIFIC_NOT_EVALUATED));
        verifyNoInteractions(ingredients);
    }

    @Test
    void Claim마다_확정_원료가_다르면_예외를_던진다() {
        ScriptedEvaluator evaluator = new ScriptedEvaluator(Map.of(RULE_A, always(MATCHED)));
        List<RuleAnalysisRequest> requests = List.of(
                new RuleAnalysisRequest(
                        new Claim("claim-1", "가", PRODUCT_COPY, "page-1#copy: 주변 문맥 확인됨"),
                        List.of(), Set.of(1L), null),
                // 원료 매칭은 상품 단위로 한 번만 확정되므로 같은 분석 안의 모든 Claim은 같은
                // 확정 원료 집합을 공유해야 한다 — 여기서는 그 전제를 일부러 깨뜨린다.
                new RuleAnalysisRequest(
                        new Claim("claim-2", "나", PRODUCT_COPY, "page-1#copy: 주변 문맥 확인됨"),
                        List.of(), Set.of(2L), null));

        assertThatThrownBy(() -> service(evaluator).analyzeAll(requests))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 확정 원료 집합을 공유");
    }

    private RuleAnalysisService service(RuleEvaluator evaluator) {
        return new RuleAnalysisService(selector, new RuleEvaluatorRegistry(List.of(evaluator)),
                new RuleSourceResolver(sources));
    }

    private static List<RuleAnalysisRequest> requests(String... texts) {
        List<RuleAnalysisRequest> requests = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            requests.add(new RuleAnalysisRequest(
                    new Claim("claim-" + (i + 1), texts[i], PRODUCT_COPY, "page-1#copy: 주변 문맥 확인됨"),
                    List.of(), Set.of(), null));
        }
        return List.copyOf(requests);
    }

    private static RuleAnalysisResult.RuleMatch matchOf(RuleAnalysisResult result, Rule rule) {
        return result.matches().stream()
                .filter(m -> m.ruleId().equals(rule.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("규칙 " + rule.getRuleCode() + " 의 판정이 없음"));
    }

    private static RuleEvaluation evaluation(RuleEvaluation.Status status) {
        return new RuleEvaluation(status, switch (status) {
            case MATCHED -> RuleEvaluation.ReasonCode.SUPPORTED_CONDITION_CONFIRMED;
            case NOT_MATCHED -> RuleEvaluation.ReasonCode.CONDITION_NOT_MET;
            case REVIEW_REQUIRED -> SEMANTIC_COMPARISON_REQUIRED;
        }, "대역 판정");
    }

    private static ScriptedEvaluator.Behavior always(RuleEvaluation.Status status) {
        return requests -> requests.stream().map(request -> evaluation(status)).toList();
    }

    /** 규칙 코드마다 동작을 지정할 수 있는 대역 — 어느 축으로 몇 번 불렸는지를 기록한다. */
    private static final class ScriptedEvaluator implements RuleEvaluator {
        interface Behavior {
            List<RuleEvaluation> apply(List<RuleAnalysisRequest> requests);
        }

        private final Map<String, Behavior> behaviors;
        final List<String> batchCalls = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger singleCalls = new AtomicInteger();

        ScriptedEvaluator(Map<String, Behavior> behaviors) {
            this.behaviors = new LinkedHashMap<>(behaviors);
        }

        @Override
        public Set<String> ruleCodes() {
            return behaviors.keySet();
        }

        @Override
        public RuleEvaluation evaluate(Rule rule, RuleAnalysisRequest request) {
            singleCalls.incrementAndGet();
            return behaviors.get(rule.getRuleCode()).apply(List.of(request)).getFirst();
        }

        @Override
        public List<RuleEvaluation> evaluateAcrossClaims(Rule rule, List<RuleAnalysisRequest> requests) {
            batchCalls.add(rule.getRuleCode());
            batchSizes.add(requests.size());
            return behaviors.get(rule.getRuleCode()).apply(requests);
        }
    }
}
