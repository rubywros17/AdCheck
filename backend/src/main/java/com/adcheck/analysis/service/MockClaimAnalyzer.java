package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageImageEvidence;
import com.adcheck.analysis.dto.PageTextEvidence;

import java.util.ArrayList;
import java.util.List;

/**
 * 개발 단계의 API 흐름 검증을 위한 단순 키워드 구현이다.
 * 실제 효능 또는 법적 위반 여부를 판단하지 않으며, 향후 분석 파이프라인으로 교체된다.
 *
 * <p>Spring Bean 등록은 이 클래스에서 직접 하지 않는다 — 조건부 등록(다른
 * {@code ClaimAnalyzer} Bean이 없을 때만 이 Mock을 대신 띄우는 것)은
 * {@link com.adcheck.analysis.config.ClaimAnalyzerConfiguration}이 담당한다.
 * ({@code @ConditionalOnMissingBean}을 이 클래스 자신에게 직접 붙이면, 이 클래스가
 * 검색 대상 타입 {@code ClaimAnalyzer} 자신을 구현하고 있어 자기 자신과의
 * 자기제외(self-exclusion)가 신뢰성 있게 동작하지 않아 Bean이 아예 등록되지 않는
 * 문제가 실측 확인됨 — Spring Boot 공식 문서도 이 조건은 auto-configuration의
 * {@code @Bean} 메서드에서만 쓰도록 권고한다.)
 *
 * <p>텍스트마다 Claim 하나를 추출하고, 주의 키워드가 있으면 그 Claim에 대한
 * {@link RiskSignalCandidate}를 하나 만든다 — 이전엔 여기서 바로 {@code Finding}을
 * 만들었지만, {@link ClaimAnalyzer}가 AI 1차 추출 후보만 반환하는 새 계약으로 바뀌면서
 * Finding 조립은 전부 {@code AnalysisBackgroundJob} 쪽 책임이 됐다. Product/Ingredient
 * 후보는 이 Mock이 추출하지 않아 항상 빈 리스트다.
 */
public class MockClaimAnalyzer implements ClaimAnalyzer {

    private static final List<String> CAUTION_KEYWORDS = List.of(
            "치료", "완치", "예방", "100%", "무조건", "완벽", "회복"
    );

    @Override
    public ClaimAnalysisResult analyze(List<PageTextEvidence> texts, List<PageImageEvidence> images) {
        List<ExtractedClaim> claims = new ArrayList<>();
        List<RiskSignalCandidate> riskSignalCandidates = new ArrayList<>();

        int index = 1;
        for (PageTextEvidence text : texts) {
            String claimId = "claim-" + index++;
            Source source = Source.domText(text.selector());
            ExtractedClaim claim = new ExtractedClaim(claimId, text.content(), source);
            claims.add(claim);

            if (containsCautionKeyword(text.content())) {
                riskSignalCandidates.add(new RiskSignalCandidate(
                        claimId, text.content(), "FUNCTION_CLAIM", null, source
                ));
            }
        }

        return new ClaimAnalysisResult(claims, List.of(), List.of(), riskSignalCandidates);
    }

    private boolean containsCautionKeyword(String content) {
        return CAUTION_KEYWORDS.stream().anyMatch(content::contains);
    }
}
