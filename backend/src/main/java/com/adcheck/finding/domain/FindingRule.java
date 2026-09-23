package com.adcheck.finding.domain;

import java.util.List;

/**
 * 한 Claim에 대해 판정된 규칙 하나. {@link Finding}이 이 목록을 들고 있어서, 화면이 대표 규칙
 * 하나만이 아니라 판정된 규칙 전체를 보여줄 수 있다.
 *
 * <p><b>왜 추가했나</b>: 예전에는 {@code FindingAssembler}가 MATCHED 중 가장 심각한 하나만 골라
 * ({@code mostSevere}) 그 규칙의 카테고리·위험도·근거로 Finding을 채우고 나머지는 버렸다.
 * 그래서 두 가지 문제가 있었다 —
 * <ul>
 *   <li><b>정보 손실</b>: 같은 Claim에 여러 규칙이 걸려도 하나만 화면에 나왔고, {@code matched}가
 *       하나라도 있으면 {@code reviewRequired}는 통째로 폐기됐다.</li>
 *   <li><b>편차 증폭</b>: 규칙 판정 자체는 실측 92% 안정인데, 대표 하나가 카테고리·위험도·근거를
 *       전부 결정하다 보니 <b>규칙 12개 중 11개가 같아도 흔들린 1개가 대표였으면 화면이 통째로
 *       바뀌었다</b>. 판정보다 최종 결과가 훨씬 불안정해 보이던 원인이다.</li>
 * </ul>
 *
 * <p>{@link Finding}의 기존 필드({@code riskLevel}·{@code category}·{@code sources})는 그대로
 * 대표 규칙 기준으로 유지한다 — 이 목록을 무시하면 이전과 동일하게 동작하므로, 프론트가 준비되기
 * 전에 백엔드만 먼저 배포해도 화면이 깨지지 않는다.
 *
 * @param status {@code MATCHED}(위반 확정)와 {@code REVIEW_REQUIRED}(사람 확인 필요)만 담는다.
 *               {@code NOT_MATCHED}는 애초에 Finding을 만들지 않으므로 여기 오지 않는다.
 * @param reason 규칙 엔진이 남긴 판정 근거. 소비자용 설명({@link Finding#message})과 달리 AI가
 *               다시 쓰지 않은 원문이라, 왜 그 규칙이 걸렸는지 그대로 보여줄 수 있다.
 */
public record FindingRule(
        String ruleCode,
        String category,
        RiskLevel riskLevel,
        String status,
        String reason,
        List<FindingSource> sources
) {
    public FindingRule {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
