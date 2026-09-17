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
     * 아래 REVIEW_REQUIRED 그룹핑/상한 테스트 3개는 전부 "이 제품은 피로 개선 효과를
     * 보장하지 않습니다."를 claim 텍스트로 쓴다 — 이 문구는 C07의 NO_GUARANTEE 패턴과
     * 정확히 일치해 C07이 NOT_MATCHED로 빠지므로(위 {@link #notMatchedProducesNoFinding()}
     * 참고), {@code @BeforeEach}가 항상 심는 C07이 REVIEW_REQUIRED 목록에 섞이지 않는다.
     * 그래서 각 테스트가 추가로 심는 평가기 없는(UNSUPPORTED_RULE → 항상 REVIEW_REQUIRED)
     * 규칙들만으로 결과를 정확히 통제할 수 있다.
     */
    @Test
    void twoDistinctReviewRequiredCategoriesProduceSeparateFindings() {
        seedUnsupportedRule("T01_CAT_A", "TEST_CATEGORY_A", "HIGH");
        seedUnsupportedRule("T02_CAT_B", "TEST_CATEGORY_B", "CAUTION");

        ClaimAnalysisResult claimResult = singleClaimResult("이 제품은 피로 개선 효과를 보장하지 않습니다.");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings()).extracting(finding -> finding.category())
                .containsExactlyInAnyOrder("TEST_CATEGORY_A", "TEST_CATEGORY_B");
        org.mockito.Mockito.verifyNoInteractions(geminiClaimComparisonService);
    }

    @Test
    void sameCategoryReviewRequiredMatchesMergeToMostSevere() {
        seedUnsupportedRule("T03_SAME_LOW", "TEST_CATEGORY_SAME", "CAUTION");
        seedUnsupportedRule("T04_SAME_HIGH", "TEST_CATEGORY_SAME", "HIGH");

        ClaimAnalysisResult claimResult = singleClaimResult("이 제품은 피로 개선 효과를 보장하지 않습니다.");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).hasSize(1);
        var finding = result.findings().getFirst();
        assertThat(finding.category()).isEqualTo("TEST_CATEGORY_SAME");
        assertThat(finding.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void moreThanCapReviewRequiredCategoriesAreTruncatedBySeverity() {
        seedUnsupportedRule("T05_HIGH_1", "TEST_CATEGORY_HIGH_1", "HIGH");
        seedUnsupportedRule("T06_HIGH_2", "TEST_CATEGORY_HIGH_2", "HIGH");
        seedUnsupportedRule("T07_CAUTION", "TEST_CATEGORY_CAUTION", "CAUTION");
        seedUnsupportedRule("T08_NORMAL", "TEST_CATEGORY_NORMAL", "NORMAL");

        ClaimAnalysisResult claimResult = singleClaimResult("이 제품은 피로 개선 효과를 보장하지 않습니다.");

        FindingAssembler.Result result = findingAssembler.assemble(claimResult);

        assertThat(result.findings()).hasSize(3);
        assertThat(result.findings()).extracting(finding -> finding.category())
                .doesNotContain("TEST_CATEGORY_NORMAL");
        assertThat(result.findings()).extracting(finding -> finding.riskLevel())
                .doesNotContain(RiskLevel.NORMAL);
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
