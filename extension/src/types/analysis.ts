import type { PageEvidence } from "./evidence";

export type CreateAnalysisRequest = PageEvidence;

export type AnalysisStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";
// Rule Engine severity tier. Drives categoryTheme.ts's badge color mapping.
export type RiskLevel = "HIGH" | "CAUTION" | "NORMAL";
// Open string type: Rule Engine ships 71+ categories and grows independently of the client.
// categoryTheme.ts's CATEGORY_MAP names the ones the client currently has copy/colors for;
// anything else safely falls back through getCategoryTheme().
export type FindingCategory = string;

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
