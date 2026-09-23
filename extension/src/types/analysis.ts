import type { PageEvidence } from "./evidence";

export type CreateAnalysisRequest = PageEvidence;

export type AnalysisStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";
// Rule Engine severity tier. Drives judgmentCategories.ts's badge color mapping.
export type RiskLevel = "HIGH" | "CAUTION" | "NORMAL";
// Backend의 judgmentCategory는 71종 문자열을 그대로 통과시키는 열린 값이라(Finding.java 참고)
// 여기서 특정 값으로 좁히지 않는다 — 좁히면 신규/미리스트업 카테고리가 올 때마다 깨진다.
// judgmentCategories.ts의 CATEGORY_MAP이 현재 라벨/색상을 아는 것들만 이름 붙이고,
// 나머지는 getCategoryTheme()의 fallback이 안전하게 처리한다.
export type FindingCategory = string;

export interface AnalysisSummary {
  findingCount: number;
  officialFunctionMatchedCount: number;
}

export interface FindingSource {
  title: string;
  section: string | null;
  sourceUrl: string | null;
}

/**
 * 이 Claim에 대해 판정된 규칙 하나. riskLevel/category/sources는 여전히 대표 규칙 기준으로
 * FindingResponse에 그대로 담기므로, 이 목록을 무시하면 이전과 동일하게 동작한다.
 *
 * status는 "MATCHED"(위반 확정) 또는 "REVIEW_REQUIRED"(사람 확인 필요)이고, 목록은
 * MATCHED가 앞·REVIEW_REQUIRED가 뒤이며 각 구간은 severity 내림차순이다 — 앞에서부터
 * N개만 펼치면 된다.
 */
export interface FindingRule {
  ruleCode: string;
  category: string;
  riskLevel: RiskLevel;
  status: string;
  reason: string | null;
  sources: FindingSource[];
}

export interface FindingResponse {
  sourceText: string;
  selector: string | null;
  riskLevel: RiskLevel;
  category: FindingCategory;
  message: string;
  officialFunction: string | null;
  sources: FindingSource[];
  rules: FindingRule[];
}

export interface AnalysisResponse {
  analysisId: number;
  status: AnalysisStatus;
  summary: AnalysisSummary;
  findings: FindingResponse[];
}
