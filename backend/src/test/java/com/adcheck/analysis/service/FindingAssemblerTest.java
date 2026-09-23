package com.adcheck.analysis.service;

import com.adcheck.finding.domain.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link FindingAssembler}이 실제 {@link com.adcheck.rule.service.RuleAnalysisService}
 * (DB 기반)와 연결되어 MATCHED/REVIEW_REQUIRED/NOT_MATCHED 세 경로를 각각 올바르게 처리하는지
 * 검증한다. C07_ABSOLUTE_EFFECT 규칙 하나만 {@code jdbc}로 직접 심어 사용한다 — {@code Rule}
 * 엔티티는 protected 기본 생성자 + getter만 있고 setter가 없어(rule/ 패키지를 건드리지
 * 않기 위해 그대로 유지), {@code RuleRepositoryIntegrationTest}가 쓰는 것과 동일한 JdbcTemplate
 * 직접 삽입 방식을 그대로 따랐다. 값은 {@code CommonRuleEvaluator.DEFINITIONS}의
 * C07_ABSOLUTE_EFFECT canonical 정의(rule_version=0.1, application_conditions/exceptions
 * 원문, required_evidence=null)와 정확히 일치해야 이 규칙이 실제로 평가된다.
 *
 * <p>Product/Ingredient 확정은 세 테스트 모두 관여하지 않는다(productCandidates가 비어
 * product=null로 남아도 C07은 원료 확정과 무관하게 평가 가능) — 그래서 원료 테이블 seed는
 * 하지 않는다. {@link GeminiClaimComparisonService}만 실제 Gemini 호출 없이 결정론적으로
 * 동작하도록 {@code @MockitoBean}으로 대체한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FindingAssemblerTest {

    @Autowired
    private FindingAssembler findingAssembler;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private GeminiClaimComparisonService geminiClaimComparisonService;

    @BeforeEach
    void seedC07Rule() {
        jdbc.update("""
                insert into rules(rule_code, scope_type, expression_type, judgment_category,
                    application_conditions, exceptions, required_evidence, severity, rule_version, review_status)
                values (?, 'COMMON', ?, 'ABSOLUTE_EFFECT', ?, ?, null, 'HIGH', '0.1', 'APPROVED')
                """,
                "C07_ABSOLUTE_EFFECT",
                "100% 효과·무조건·완벽한 회복",
                "수식 대상이 건강 효과이고 개인차 없이 결과를 확정하는지 확인",
                "원료함량·영양성분 기준치·배송 안내 등과 구분. 예시 조합 일부는 AdCheck 작성 예시"
        );
    }

    @Test
    void matchedRuleTriggersAi2AndProducesFindingWithExplanation() {
        when(geminiClaimComparisonService.compare(any())).thenReturn(new ClaimComparisonResult(List.of(
                new ClaimComparison(
                        "claim-1",
                        "STRONGER_THAN_OFFICIAL",
                        null,
                        "100% 효과 보장 표현이 확인됨",
                        "이 표현은 개인차 없이 효과를 확정적으로 보장한다고 말하고 있어 과장 표현일 수 있어요."
                )
        )));

        ClaimAnalysisResult claimResult = singleClaimResult("100% 효과를 보장합니다.");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).hasSize(1);
        var finding = result.findings().getFirst();
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(finding.category()).isEqualTo("ABSOLUTE_EFFECT");
        assertThat(finding.message()).isEqualTo("이 표현은 개인차 없이 효과를 확정적으로 보장한다고 말하고 있어 과장 표현일 수 있어요.");
        assertThat(finding.sourceText()).isEqualTo("100% 효과를 보장합니다.");
    }

    @Test
    void reviewRequiredOnlyProducesFixedMessageFindingWithoutCallingAi2() {
        ClaimAnalysisResult claimResult = singleClaimResult("정말 좋은 효과가 있어요");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).hasSize(1);
        var finding = result.findings().getFirst();
        assertThat(finding.message()).isEqualTo("확인이 필요한 표현입니다.");
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.HIGH);
        org.mockito.Mockito.verifyNoInteractions(geminiClaimComparisonService);
    }

    @Test
    void notMatchedProducesNoFinding() {
        ClaimAnalysisResult claimResult = singleClaimResult("이 제품은 피로 개선 효과를 보장하지 않습니다.");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(geminiClaimComparisonService);
    }

    /**
     * 아래 두 테스트는 "이 제품은 피로 개선 효과를 보장하지 않습니다."를 claim 텍스트로 쓴다
     * — 이 문구는 C07의 NO_GUARANTEE 패턴과 정확히 일치해 C07이 NOT_MATCHED로 빠지므로(위
     * {@link #notMatchedProducesNoFinding()} 참고), {@code @BeforeEach}가 항상 심는 C07이
     * REVIEW_REQUIRED 목록에 섞이지 않는다. 그래서 추가로 심는 평가기 없는 더미 규칙만으로
     * "reasonCode=UNSUPPORTED_RULE만 있으면 Finding 없음"을 정확히 통제해서 검증할 수 있다.
     */
    @Test
    void reviewRequiredWithOnlyUnsupportedRuleProducesNoFinding() {
        seedUnsupportedRule("T01_ONLY_UNSUPPORTED", "TEST_CATEGORY_ONLY", "HIGH");

        ClaimAnalysisResult claimResult = singleClaimResult("이 제품은 피로 개선 효과를 보장하지 않습니다.");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(geminiClaimComparisonService);
    }

    @Test
    void unsupportedRuleAloneWithoutAnyGenuineReviewRequiredAlsoProducesNoFinding() {
        seedUnsupportedRule("T02_UNSUPPORTED_A", "TEST_CATEGORY_A", "HIGH");
        seedUnsupportedRule("T03_UNSUPPORTED_B", "TEST_CATEGORY_B", "CAUTION");

        ClaimAnalysisResult claimResult = singleClaimResult("이 제품은 피로 개선 효과를 보장하지 않습니다.");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).isEmpty();
    }

    /**
     * "정말 좋은 효과가 있어요"는 C07을 진짜(reasonCode=OUTSIDE_SUPPORTED_LANGUAGE)
     * REVIEW_REQUIRED로 판정한다({@link #reviewRequiredOnlyProducesFixedMessageFindingWithoutCallingAi2}
     * 참고). 여기에 rule_code가 알파벳순으로 "C07"보다 앞서는(그래서 필터링이 없다면
     * {@code mostSevere()}의 동점 tie-break에서 이겨버리는) UNSUPPORTED_RULE 더미 규칙을
     * 같은 HIGH severity로 추가해서, reasonCode 필터링이 실제로 UNSUPPORTED_RULE을
     * 제외하고 진짜 REVIEW_REQUIRED(C07)만 남기는지 검증한다.
     */
    @Test
    void genuineReviewRequiredSurvivesWhileUnsupportedRuleIsExcludedEvenIfItWouldWinByOrder() {
        seedUnsupportedRule("A01_DUMMY_UNSUPPORTED", "TEST_CATEGORY_DUMMY", "HIGH");

        ClaimAnalysisResult claimResult = singleClaimResult("정말 좋은 효과가 있어요");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).hasSize(1);
        var finding = result.findings().getFirst();
        assertThat(finding.category()).isEqualTo("ABSOLUTE_EFFECT");
        assertThat(finding.message()).isEqualTo("확인이 필요한 표현입니다.");
        org.mockito.Mockito.verifyNoInteractions(geminiClaimComparisonService);
    }

    @Test
    void 판정된_규칙을_rules에_모두_담고_대표_규칙은_기존_필드에_그대로_남는다() {
        ClaimAnalysisResult claimResult = singleClaimResult("정말 좋은 효과가 있어요");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        var finding = result.findings().getFirst();
        // 기존 필드는 대표 규칙 기준으로 그대로 — rules를 무시하면 이전과 동일하게 동작한다.
        assertThat(finding.category()).isEqualTo("ABSOLUTE_EFFECT");
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.HIGH);
        // 판정된 규칙이 목록으로도 담긴다.
        assertThat(finding.rules()).isNotEmpty();
        assertThat(finding.rules().getFirst().ruleCode()).isNotBlank();
        assertThat(finding.rules()).allSatisfy(rule ->
                assertThat(rule.status()).isIn("MATCHED", "REVIEW_REQUIRED"));
    }

    @Test
    void 평가기가_없는_규칙은_rules에도_담기지_않는다() {
        seedUnsupportedRule("A02_DUMMY_UNSUPPORTED", "TEST_CATEGORY_DUMMY2", "HIGH");

        ClaimAnalysisResult claimResult = singleClaimResult("정말 좋은 효과가 있어요");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        var finding = result.findings().getFirst();
        assertThat(finding.rules())
                .noneMatch(rule -> "A02_DUMMY_UNSUPPORTED".equals(rule.ruleCode()));
    }

    /**
     * 아래 네 테스트는 {@code officialFunction} 보완 규칙을 직접 검증한다.
     *
     * <p>배경(2026-09-23 실측): 저장된 Finding 143건 중 공식 인정 문구가 붙은 건 3건(2%)뿐이었다.
     * 채우는 곳이 AI#2 하나뿐인데 AI#2는 {@code matched}가 있는 Claim에만 호출되고, 나머지
     * 109건은 {@code null}이 하드코딩돼 <b>구조적으로</b> 나올 수 없었다.
     *
     * <p>보완은 "Claim 본문에 확정 원료의 표준명이 실제로 등장할 때만 붙인다"는 결정론적 규칙이다.
     * 근거 없이 붙이면 엉뚱한 기능성을 근거처럼 보여주게 되므로, <b>안 붙이는 경우</b>를 함께
     * 고정해 둔다 — 이쪽이 빠지면 "제품에 있는 아무 문구나 붙이기"라는 퇴행을 통과시킨다.
     */
    @Test
    void 공식문구는_Claim에_원료명이_등장할_때만_붙는다() {
        List<OfficialFunction> officialFunctions = List.of(
                new OfficialFunction("밀크씨슬 추출물", "간 건강에 도움을 줄 수 있음"),
                new OfficialFunction("루테인", "눈 건강에 도움을 줄 수 있음"));

        // 표기 공백이 달라도("밀크씨슬 추출물" vs "밀크씨슬추출물") 같은 원료로 본다.
        assertThat(findingAssembler.resolveOfficialFunction(
                null, "밀크씨슬추출물이 간을 완벽하게 되살려 줍니다", officialFunctions))
                .isEqualTo("간 건강에 도움을 줄 수 있음");

        // 확정 원료이긴 해도 Claim에 이름이 없으면 붙이지 않는다.
        assertThat(findingAssembler.resolveOfficialFunction(
                null, "단 2주 만에 피로가 완전히 사라집니다", officialFunctions))
                .isNull();
    }

    @Test
    void AI가_채운_공식문구가_있으면_그대로_둔다() {
        List<OfficialFunction> officialFunctions = List.of(
                new OfficialFunction("루테인", "눈 건강에 도움을 줄 수 있음"));

        assertThat(findingAssembler.resolveOfficialFunction(
                "AI가 고른 문구", "루테인 함유", officialFunctions))
                .isEqualTo("AI가 고른 문구");
        // 빈 문자열은 "채워진 값"으로 보지 않고 보완 대상으로 삼는다.
        assertThat(findingAssembler.resolveOfficialFunction(
                "  ", "루테인 함유", officialFunctions))
                .isEqualTo("눈 건강에 도움을 줄 수 있음");
    }

    @Test
    void 한_글자_원료명은_아무_문장에나_걸리므로_제외한다() {
        List<OfficialFunction> officialFunctions = List.of(
                new OfficialFunction("철", "체내 산소 운반과 혈액 생성에 필요"));

        // "철"은 "철저히"에도 들어 있어 오탐이 된다.
        assertThat(findingAssembler.resolveOfficialFunction(
                null, "철저히 관리된 원료만 씁니다", officialFunctions))
                .isNull();
    }

    @Test
    void 이름이_겹치면_더_구체적인_원료를_고른다() {
        List<OfficialFunction> officialFunctions = List.of(
                new OfficialFunction("비타민B1", "탄수화물과 에너지 대사에 필요"),
                new OfficialFunction("비타민B12", "정상적인 엽산 대사에 필요"));

        assertThat(findingAssembler.resolveOfficialFunction(
                null, "비타민B12가 풍부하게 들어 있습니다", officialFunctions))
                .isEqualTo("정상적인 엽산 대사에 필요");
    }

    /** rule_code만 다르고 평가기가 없는(=항상 UNSUPPORTED_RULE→REVIEW_REQUIRED) 더미 규칙을 심는다. */
    private void seedUnsupportedRule(String ruleCode, String judgmentCategory, String severity) {
        jdbc.update("""
                insert into rules(rule_code, scope_type, expression_type, judgment_category,
                    application_conditions, exceptions, required_evidence, severity, rule_version, review_status)
                values (?, 'COMMON', 'TEST', ?, 'TEST', 'TEST', null, ?, '0.1', 'APPROVED')
                """,
                ruleCode, judgmentCategory, severity
        );
    }

    private ClaimAnalysisResult singleClaimResult(String claimText) {
        ExtractedClaim claim = new ExtractedClaim("claim-1", claimText, Source.domText("#claim"));
        return new ClaimAnalysisResult(List.of(claim), List.of(), List.of(), List.of());
    }
}
