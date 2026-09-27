package com.adcheck.analysis.dto;

/**
 * 분석 1건의 요약.
 *
 * @param findingCount              화면에 띄울 Finding 수 — 위반이 의심되거나 사람이 볼 필요가
 *                                  있다고 <b>평가기가 판단한</b> 것만 센다.
 * @param officialFunctionMatchedCount 공식 인정 기능성이 붙은 Finding 수.
 * @param unevaluatedClaimCount     평가기의 지원 범위를 벗어나 <b>판단하지 못한</b> 문구 수.
 *        {@code findingCount}에서 빠지지만 "문제 없음"과는 다르다 — 확인을 못 한 것이므로
 *        사실 자체는 알려야 한다. 이 값이 빠지면 "위험 2건"이 "나머지는 괜찮음"으로 읽힌다.
 */
public record AnalysisSummary(int findingCount, int officialFunctionMatchedCount,
                              int unevaluatedClaimCount) {
}
