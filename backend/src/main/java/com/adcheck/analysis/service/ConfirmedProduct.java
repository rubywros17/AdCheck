package com.adcheck.analysis.service;

/**
 * Backend가 확정한 Product — AI #2(Comparison) 입력. AI가 만드는 게 아니라 Backend가
 * {@link ProductCandidate}를 공식 DB와 대조해서 확정한 뒤 넘겨주는 값이다.
 */
public record ConfirmedProduct(String productReportNo, String productName) {
}
