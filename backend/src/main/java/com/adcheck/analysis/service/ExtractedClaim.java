package com.adcheck.analysis.service;

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
 */
public record ExtractedClaim(String claimId, String claimText, Source source) {
}
