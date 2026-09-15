package com.adcheck.analysis.service;

/**
 * AI 1차 추출이 찾아낸 주의 필요 표현 후보 1건 — Backend Contract 기준.
 * claimId로 어느 Claim에서 나온 신호인지 연결한다. signalType은 Rule 기준을 참고한
 * 분류(예: ABSOLUTE_EFFECT, DISEASE_TREATMENT, DISEASE_PREVENTION)이며, 최종 ruleCode/
 * severity/법적 위반 여부 판정은 하지 않는다 — 그건 Backend RuleAnalysisService의 몫이다.
 *
 * <p>추출 로직은 아직 구현되지 않아 현재는 항상 빈 리스트로 반환된다.
 */
public record RiskSignalCandidate(
        String claimId,
        String text,
        String signalType,
        Double confidence,
        Source source
) {
}
