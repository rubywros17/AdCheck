package com.adcheck.analysis.service;

/**
 * AI 1차 추출이 페이지에서 읽은 제품 후보 1건 — Backend Contract 기준.
 * productReportNo/productName/companyName은 페이지에서 못 찾으면 null일 수 있다.
 * Backend는 productReportNo exact match를 우선 시도하고, 없으면 productName+companyName으로
 * 조회해서 공식 Product를 확정한다 — AI는 신고번호를 임의로 추정·생성하지 않는다.
 *
 * <p>추출 로직은 아직 구현되지 않아 현재는 항상 빈 리스트로 반환된다.
 */
public record ProductCandidate(
        String productReportNo,
        String productName,
        String companyName,
        Double confidence,
        Source source
) {
}
