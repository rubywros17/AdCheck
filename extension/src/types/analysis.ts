import type { PageEvidence } from "./evidence";

export type CreateAnalysisRequest = PageEvidence;

export type AnalysisStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";
export type RiskLevel = "CAUTION";
export type FindingCategory = "FUNCTION_CLAIM";

export interface AnalysisSummary {
  findingCount: number;
  officialFunctionMatchedCount: number;
}

export interface FindingResponse {
  sourceText: string;
  selector: string | null;
  riskLevel: RiskLevel;
  category: FindingCategory;
  message: string;
  officialFunction: string | null;
}

export interface AnalysisResponse {
  analysisId: number;
  status: AnalysisStatus;
  summary: AnalysisSummary;
  findings: FindingResponse[];
}
