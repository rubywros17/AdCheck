package com.adcheck.analysis.service;

/**
 * RAG가 검색한 공식 근거 문단 — AI #2(Comparison)가 설명(explanation) 생성에 참고하고,
 * Backend는 최종 Finding에서 근거 위치 표시·검증에 사용한다.
 *
 * <p>{@code chunkId}는 현재 "1페이지=1청크" 구조라 {@code sourceId + "-p" + pdfPage} 형태로
 * 구성한다 — 청크 단위가 나중에 바뀌면 이 조합 방식도 같이 바뀔 수 있다.
 * {@code score}는 질의 벡터와의 코사인 유사도(0~1에 가까울수록 관련성이 높음).
 */
public record Evidence(String chunkId, String sourceId, Integer pdfPage, String text, Double score) {
}
