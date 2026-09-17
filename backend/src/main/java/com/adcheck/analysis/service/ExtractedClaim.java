package com.adcheck.analysis.service;

import com.adcheck.rule.service.RuleAnalysisRequest;

/**
 * Claim 추출 파이프라인의 결과 1건.
 *
 * <p>claimId는 이 분석 1건 안에서만 유일하면 되는 로컬 식별자다(Backend Contract 기준
 * "claim-1", "claim-2"... 형태로 충분) — Rule/RiskSignal/2차 비교 단계에서 같은 Claim을
 * 다시 가리킬 때 쓴다.
 *
 * <p>규칙(rule) 위반 판정은 이 서비스가 하지 않는다 — Backend Rule Engine이 DB의
 * {@code rules} 테이블(적용 조건/예외/근거 등)을 기준으로 판정하는 몫이라, AI는 "무슨
 * 주장을 했는지"만 뽑는다.
 *
 * <p>{@code context}/{@code contextEvidence}는 {@link ProductContentExtractionService}가
 * 페이지 전체를 보고 직접 판단한 값이다(2026-09-16 추가) — 다만 지금 실제 파이프라인은
 * 여전히 {@code ClaimContextClassifier}(source 종류만 보는 최소 구현)가 채운 값을 쓰고
 * 있어서, 이 필드는 아직 어디에도 소비되지 않는 추가 데이터다. 어느 쪽 값을 실제로 쓸지는
 * Rule Engine 쪽과 합의가 필요해 별도로 남겨둔다. 3개 인자 생성자는 이 필드들을 모르는
 * 기존 호출부(MockClaimAnalyzer, 각종 테스트)가 그대로 컴파일되도록 남겨둔 것이다.
 */
public record ExtractedClaim(
        String claimId, String claimText, Source source,
        RuleAnalysisRequest.Context context, String contextEvidence
) {
    public ExtractedClaim {
        context = context == null ? RuleAnalysisRequest.Context.UNKNOWN : context;
    }

    public ExtractedClaim(String claimId, String claimText, Source source) {
        this(claimId, claimText, source, RuleAnalysisRequest.Context.UNKNOWN, null);
    }
}
