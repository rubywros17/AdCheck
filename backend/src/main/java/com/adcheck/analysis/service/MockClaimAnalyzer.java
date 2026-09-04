package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageTextEvidence;
import com.adcheck.finding.domain.Finding;
import com.adcheck.finding.domain.FindingCategory;
import com.adcheck.finding.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 개발 단계의 API 흐름 검증을 위한 단순 키워드 구현이다.
 * 실제 효능 또는 법적 위반 여부를 판단하지 않으며, 향후 분석 파이프라인으로 교체된다.
 */
@Component
public class MockClaimAnalyzer implements ClaimAnalyzer {

    private static final List<String> CAUTION_KEYWORDS = List.of(
            "치료", "완치", "예방", "100%", "무조건", "완벽", "회복"
    );

    @Override
    public ClaimAnalysisResult analyze(List<PageTextEvidence> texts) {
        List<Finding> findings = new ArrayList<>();
        int officialFunctionMatchedCount = 0;

        for (PageTextEvidence text : texts) {
            if (looksLikePermittedFunctionWording(text.content())) {
                officialFunctionMatchedCount++;
            }
            if (containsCautionKeyword(text.content())) {
                findings.add(createMockFinding(text));
            }
        }

        return new ClaimAnalysisResult(findings, officialFunctionMatchedCount);
    }

    private boolean containsCautionKeyword(String content) {
        return CAUTION_KEYWORDS.stream().anyMatch(content::contains);
    }

    private boolean looksLikePermittedFunctionWording(String content) {
        return content.contains("도움을 줄 수 있습니다") || content.contains("도움을 줄 수 있음");
    }

    private Finding createMockFinding(PageTextEvidence text) {
        String officialFunction = containsEyeHealthTerm(text.content())
                ? "눈 건강에 도움을 줄 수 있음"
                : "공식 인정 기능성 정보와 비교 확인이 필요합니다.";

        return new Finding(
                text.content(),
                text.selector(),
                RiskLevel.CAUTION,
                FindingCategory.FUNCTION_CLAIM,
                "공식 인정 기능성보다 강한 표현일 가능성이 있습니다.",
                officialFunction
        );
    }

    private boolean containsEyeHealthTerm(String content) {
        return content.contains("눈") || content.contains("시력") || content.contains("노안");
    }
}
