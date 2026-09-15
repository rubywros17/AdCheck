package com.adcheck.analysis.service;

/**
 * AI 1차 추출 결과(Claim/Product/Ingredient/RiskSignal Candidate)가 페이지 어디서
 * 나왔는지를 나타내는 공통 구조 — Backend Contract(2026-09-10) 기준.
 * DOM 본문이면 selector만, 이미지 OCR이면 imageUrl만 채운다.
 */
public record Source(String sourceType, String selector, String imageUrl) {

    public static Source domText(String selector) {
        return new Source("DOM_TEXT", selector, null);
    }

    public static Source ocrImage(String imageUrl) {
        return new Source("OCR_IMAGE", null, imageUrl);
    }
}
