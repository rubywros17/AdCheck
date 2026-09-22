package com.adcheck.rule.service;

import com.adcheck.analysis.service.AiRuleEvaluator;
import com.adcheck.rule.config.RuleJudgeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.UNSUPPORTED_RULE;
import static com.adcheck.rule.service.RuleEvaluation.Status.REVIEW_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RuleJudgeProperties} allowlist가 {@link RuleEvaluatorRegistry}에서 실제로
 * 필터링되는지 확인한다 — evaluator가 규칙을 판정할 수 있어도(ruleCodes()에 있어도),
 * allowlist 밖이면 여전히 {@code UNSUPPORTED_RULE}이어야 파일럿 단계적 확대가 의도대로
 * 동작한다.
 */
class RuleEvaluatorRegistryPilotTest {

    /** context=UNKNOWN이라 AiRuleEvaluator 자체의 사전 체크에서 곧바로 걸려 geminiClient(null)까진 안 감. */
    private static RuleAnalysisRequest requestWithUnknownContext(String text) {
        return new RuleAnalysisRequest(
                new RuleAnalysisRequest.Claim("claim-1", text, RuleAnalysisRequest.Context.UNKNOWN, null),
                List.of(), java.util.Set.of(), null);
    }

    @Test
    void allowlist에_있는_규칙은_등록되고_없는_규칙은_UNSUPPORTED_RULE로_남는다() {
        RuleJudgeProperties properties = new RuleJudgeProperties();
        properties.setEnabledRuleCodes(List.of("B02_VIRUS")); // 파일럿 9개 중 1개만 허용

        RuleEvaluatorRegistry registry = new RuleEvaluatorRegistry(
                List.of(new AiRuleEvaluator(null, null)), properties);

        // allowlist에 있는 B02_VIRUS는 실제 evaluator(AiRuleEvaluator)로 라우팅됨 — registry
        // 자체의 UNSUPPORTED_RULE 기본값이 아니라 evaluator가 내놓은 사전 체크 결과(CONTEXT_
        // UNVERIFIED)가 나와야 "등록이 실제로 됐다"는 뜻이다.
        var result = registry.evaluate(
                CanonicalRuleFixture.rule("B02_VIRUS"), requestWithUnknownContext("바이러스 걱정 없는 계절을 보내세요"));
        assertThat(result.reasonCode()).isNotEqualTo(UNSUPPORTED_RULE);

        // allowlist에 없는 C13_TESTIMONIAL은 AiRuleEvaluator.ruleCodes()엔 있지만
        // 등록에서 걸러져 UNSUPPORTED_RULE로 남아야 한다.
        var filtered = registry.evaluate(
                CanonicalRuleFixture.rule("C13_TESTIMONIAL"), requestWithUnknownContext("먹고 나았다는 후기"));
        assertThat(filtered.status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(filtered.reasonCode()).isEqualTo(UNSUPPORTED_RULE);
    }

    @Test
    void 기본_RuleJudgeProperties는_Common3_Literal9_AI파일럿38_총50개를_포함한다() {
        RuleJudgeProperties defaults = new RuleJudgeProperties();
        assertThat(defaults.getEnabledRuleCodes()).hasSize(50)
                .contains("C05_FUNCTION_EXCEED", "C07_ABSOLUTE_EFFECT", "C24_OVERCONSUMPTION")
                .contains("C08_RESULT_TIME_AMOUNT", "M03_ALCOHOL", "S01_PAIN")
                .contains("B02_VIRUS", "R02_MENOPAUSE", "T04_ANTIAGING")
                .contains("C01_DISEASE_PREVENTION", "E03_GENERATION", "P03_DISEASE_GUT")
                .contains("E01_VESSEL", "L03_EYE_DISEASE", "C22_SUPERLATIVE")
                .contains("L01_VISION")
                // 5차 확장(라벨 정정 후 재검증) — 2개
                .contains("G05_GLUCOSE_DIET", "M04_REGEN_CANCER")
                // 6차 확장(개별 targeted 프롬프트 수정) — 3개
                .contains("C04_DISEASE_INFO_LINK", "C14_EXPERT_ENDORSEMENT", "C21_UNFAIR_COMPARISON")
                // 7차 확장(라벨 정정 후 재검증) — 1개
                .contains("S04_SEASON")
                // 8차 확장(미조사 백로그 targeted 프롬프트 수정) — 6개
                .contains("C11_SUB_INGREDIENT_FUNCTION", "C28_TARGET_SPECIALIZATION", "C30_NATURAL_FREE")
                .contains("G01_EASY_DIET", "L02_UV", "M01_FATIGUE")
                // 9차 확장(하네스 버그 + 라벨 오류 정정) — 2개
                .contains("B03_OTHER_ORAL", "C03_MEDICINE_CONFUSION")
                // 10차 확장(라벨 오류 정정 + 실제 사례/원문 인용 프롬프트 수정) — 4개
                .contains("S02_BODY_AREA", "E04_ALIAS", "B01_IMMUNE_INFLAMMATION", "M02_LIVER_MARKER");
    }
}
