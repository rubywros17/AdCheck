import type { PageEvidence } from "./evidence";

export type CreateAnalysisRequest = PageEvidence;

export type AnalysisStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";
export type RiskLevel = "HIGH" | "CAUTION" | "NORMAL";
// Backend의 judgmentCategory는 71종 문자열을 그대로 통과시키는 열린 값이라(Finding.java 참고)
// 여기서 특정 값으로 좁히지 않는다 — 좁히면 신규/미리스트업 카테고리가 올 때마다 깨진다.
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

export interface FindingResponse {
  sourceText: string;
  selector: string | null;
  riskLevel: RiskLevel;
  category: FindingCategory;
  message: string;
  officialFunction: string | null;
  sources: FindingSource[];
}

export interface AnalysisResponse {
  analysisId: number;
  status: AnalysisStatus;
  summary: AnalysisSummary;
  findings: FindingResponse[];
}
