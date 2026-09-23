package com.adcheck.analysis.service;

import java.util.List;
import java.util.Map;

/**
 * 이미지 URL에서 텍스트를 추출하는 OCR 역할. 구현체는 설정
 * {@code adcheck.ocr.provider}로 하나만 선택되어 주입된다.
 *
 * <p>구현이 둘인 이유는 실측 때문이다(2026-09-22): 실제 상품페이지 147장으로 비교했을 때
 * Google Cloud Vision이 Gemini 대비 추출량 96%를 유지하면서 훨씬 빨랐다(운영 조건 환산
 * 약 3.5배, 일반 페이지 기준 6~8배). 다만 작은 글씨의 오탈자는 Vision 쪽이 조금 더 많아서,
 * 문제가 생기면 즉시 되돌릴 수 있도록 Gemini 구현을 폴백으로 남겨둔다.
 */
public interface OcrService {

    /** 이미지 URL 안의 텍스트를 추출. 실패하면 빈 문자열을 반환한다. */
    String extractText(String imageUrl);

    /**
     * 이미지 URL 목록의 텍스트를 추출한다.
     *
     * @return 입력 순서를 보존하는 {@code URL -> 추출 텍스트} 맵. 다운로드·인식에 실패했거나
     *         필터에 걸러진 URL도 키는 유지하되 빈 문자열을 담는다(필터링은 호출자 몫).
     */
    Map<String, String> extractTexts(List<String> imageUrls);
}
