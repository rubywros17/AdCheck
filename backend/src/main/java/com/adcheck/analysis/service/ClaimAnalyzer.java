package com.adcheck.analysis.service;

import com.adcheck.analysis.dto.PageImageEvidence;
import com.adcheck.analysis.dto.PageTextEvidence;

import java.util.List;

/**
 * Backend가 정의한 교체 가능한 인터페이스 — {@code MockClaimAnalyzer} 자리에 실제 구현
 * ({@link GeminiClaimAnalyzer})을 꽂아 넣는 용도. 원본은
 * {@code analyze(List<PageTextEvidence> texts)}로 텍스트만 받았는데, 실제 Claim/원료
 * 추출은 대부분 이미지 OCR에서 나오므로 여기서는 이미지 파라미터를 추가해서 확장했다.
 * {@code AnalysisBackgroundJob}은 이 확장된 시그니처({@code analyze(texts, images)})로
 * 호출하고, {@link ClaimAnalysisResult}의 네 후보 리스트를 그대로 받아 Product/Ingredient
 * 확정·Rule 판정·Finding 조립에 사용한다(Backend Contract, 2026-09-10 확정).
 */
public interface ClaimAnalyzer {

    ClaimAnalysisResult analyze(List<PageTextEvidence> texts, List<PageImageEvidence> images);
}
