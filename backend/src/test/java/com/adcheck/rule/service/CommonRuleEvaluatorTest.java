package com.adcheck.rule.service;

import com.adcheck.product.service.OfficialFunctionReadModel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static com.adcheck.rule.service.RuleAnalysisRequest.*;
import static com.adcheck.rule.service.RuleAnalysisRequest.Context.*;
import static com.adcheck.rule.service.RuleEvaluation.Status.*;
import static com.adcheck.rule.service.RuleEvaluation.ReasonCode.*;

class CommonRuleEvaluatorTest {
    private final CommonRuleEvaluator evaluator = new CommonRuleEvaluator();

    static RuleAnalysisRequest request(String text) {
        return new RuleAnalysisRequest(new Claim("claim-1", text, PRODUCT_COPY, "page-1#copy: surrounding context checked"),
                Set.of(), Set.of(), null);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "C07_ABSOLUTE_EFFECT|이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다.|MATCHED",
        "C07_ABSOLUTE_EFFECT|이 제품을 섭취하면 누구나 체지방 감소 효과를 반드시 얻습니다!|MATCHED",
        "C07_ABSOLUTE_EFFECT|원료 함량은 100%입니다.|NOT_MATCHED",
        "C07_ABSOLUTE_EFFECT|영양성분 기준치는 100%입니다.|NOT_MATCHED",
        "C07_ABSOLUTE_EFFECT|이 제품은 기억력 개선 효과를 보장하지 않습니다.|NOT_MATCHED",
        "C24_OVERCONSUMPTION|균형 잡힌 식사 대신 이 제품만 드세요.|MATCHED",
        "C24_OVERCONSUMPTION|매일 식사 대신 이 제품만 섭취하세요!|MATCHED",
        "C24_OVERCONSUMPTION|이 제품은 균형 잡힌 식사를 대체할 수 없습니다.|NOT_MATCHED",
        "C24_OVERCONSUMPTION|이 제품의 1일 섭취량에는 비타민 C가 100 mg 들어 있습니다.|NOT_MATCHED"
    })
    void evaluatesBoundedCompleteSentences(String code, String text, RuleEvaluation.Status expected) {
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule(code), request(text)).status()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "100% 효과", "100% 배송 보장", "이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다.라는 주장은 사실이 아닙니다.",
        "이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다. 개인차가 있습니다.",
        "‘이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다.’라는 광고에 주의하세요.",
        "이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻는 것은 아닙니다."
    })
    void doesNotInferMatchFromKeywordsOrPartialSentences(String text) {
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT"), request(text)).status())
                .isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void riskCandidateAloneDoesNotEstablishContextOrViolation() {
        var input = new RuleAnalysisRequest(new Claim("1", "100% 효과", UNKNOWN, null),
                Set.of("C07_ABSOLUTE_EFFECT"), Set.of(), null);
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT"), input).reasonCode())
                .isEqualTo(CONTEXT_UNVERIFIED);
    }

    @Test
    void missingContextEvidenceRequiresReview() {
        var input = new RuleAnalysisRequest(new Claim("1", "매일 식사 대신 이 제품만 드세요.", PRODUCT_COPY, ""),
                null, null, null);
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule("C24_OVERCONSUMPTION"), input).status())
                .isEqualTo(REVIEW_REQUIRED);
    }

    @Test
    void confirmedIndependentInformationIsException() {
        var input = new RuleAnalysisRequest(new Claim("1", "100% 효과라는 표현을 분석한 건강정보", NON_PRODUCT_INFORMATION,
                "독립 교육자료이며 제품 귀속 없음 확인"), null, null, null);
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT"), input).reasonCode())
                .isEqualTo(EXCEPTION_CONFIRMED);
    }

    @Test
    void blankClaimIsMissingInput() {
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT"), request(" ")).reasonCode())
                .isEqualTo(MISSING_CLAIM_TEXT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ruleVersion", "applicationConditions", "exceptions", "requiredEvidence", "scopeType"})
    void changedDefinitionCannotReuseOldEvaluator(String field) {
        var rule = CanonicalRuleFixture.rule("C07_ABSOLUTE_EFFECT");
        ReflectionTestUtils.setField(rule, field, "changed");
        assertThat(evaluator.evaluate(rule, request("이 제품을 섭취하면 누구나 피로 개선 효과를 100% 얻습니다.")).reasonCode())
                .isEqualTo(RULE_DEFINITION_CHANGED);
    }

    @Test
    void exactOfficialFunctionFromAnyConfirmedMainIngredientDoesNotExceed() {
        var input = officialRequest("피로 개선에 도움을 줄 수 있음", Set.of(1L, 2L),
                List.of(function(1L, "면역력 증진에 도움을 줄 수 있음"), function(2L, "피로 개선에 도움을 줄 수 있음")), true, true);
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule("C05_FUNCTION_EXCEED"), input).reasonCode())
                .isEqualTo(OFFICIAL_FUNCTION_EXACT_MATCH);
    }

    @Test
    void officialTextMismatchIsNotAutomaticallyViolation() {
        var input = officialRequest("피로가 완전히 사라집니다", Set.of(1L),
                List.of(function(1L, "피로 개선에 도움을 줄 수 있음")), true, true);
        assertThat(evaluator.evaluate(CanonicalRuleFixture.rule("C05_FUNCTION_EXCEED"), input).reasonCode())
                .isEqualTo(SEMANTIC_COMPARISON_REQUIRED);
    }

    @Test
    void incompleteOrUnrelatedOfficialFunctionsRequireReview() {
        for (var input : List.of(
                officialRequest("피로 개선", Set.of(), List.of(), false, false),
                officialRequest("피로 개선", Set.of(1L), List.of(function(1L, "피로 개선")), false, true),
                officialRequest("피로 개선", Set.of(1L), List.of(function(1L, "피로 개선")), true, false),
                officialRequest("피로 개선", Set.of(1L, 2L), List.of(function(1L, "피로 개선")), true, true),
                officialRequest("피로 개선", Set.of(1L), List.of(function(2L, "피로 개선")), true, true),
                officialRequest("피로 개선", Set.of(1L), List.of(function(1L, null)), true, true))) {
            assertThat(evaluator.evaluate(CanonicalRuleFixture.rule("C05_FUNCTION_EXCEED"), input).reasonCode())
                    .isEqualTo(OFFICIAL_FUNCTION_DATA_INCOMPLETE);
        }
    }

    private static OfficialFunctionReadModel function(Long id, String text) {
        return new OfficialFunctionReadModel(id, "테스트 원료", text, OfficialFunctionReadModel.SourceType.NOTIFIED, null, "fixture");
    }

    private static RuleAnalysisRequest officialRequest(String text, Set<Long> ids,
            List<OfficialFunctionReadModel> values, boolean applicable, boolean complete) {
        return new RuleAnalysisRequest(request(text).claim(), Set.of(), ids, new OfficialFunctions(values, applicable, complete));
    }

    @Test
    void rejectsInvalidIdentityRatherThanReportingSystemErrorAsReview() {
        assertThatThrownBy(() -> new Claim("", "text", UNKNOWN, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleAnalysisRequest(request("text").claim(), null, Set.of(-1L), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
