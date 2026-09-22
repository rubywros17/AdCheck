package com.adcheck.finding.domain;

/**
 * Finding이 근거로 삼은 법령·심의기준 조문. {@link com.adcheck.rule.service.RuleAnalysisResult.SourceMetadata}에서
 * 인용 표시에 필요한 필드만 추려 옮긴다 — {@code referenceSourceId}/{@code verificationStatus}/
 * {@code documentVersion} 등 나머지는 지금 화면에 보여줄 근거 인용({@code "식품표시광고법 §8①1"}
 * 같은 표시)엔 불필요하다.
 *
 * <p>{@code section}은 출처 문서 전체가 다루는 조문 목록(예: "제4조제1항제3호, 제7조, 제8조제1항")을
 * 그대로 옮긴 것이라, 특정 규칙 하나에만 해당하는 조문으로 이미 좁혀진 값은 아니다.
 */
public record FindingSource(String title, String section, String sourceUrl) {
}
