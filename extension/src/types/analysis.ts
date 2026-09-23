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

export interface FindingSource {
  title: string;
  section: string | null;
  // 세미콜론(;)으로 "URL (설명)" 여러 개가 이어붙을 수 있다 — DetailListView.tsx의 parseSourceUrls()가 이걸 파싱한다.
  sourceUrl: string | null;
}

// 하나의 Claim(문장)에 여러 Rule(규칙)이 매핑될 수 있다 — 예: 광고 문구 하나가 "의약품 오인"과
// "체지방 감소 효능 과장" 두 규칙에 동시에 걸릴 수 있음. 카드 메인 콘텐츠는 대표 규칙인 rules[0]
// 기준으로 표시하고, 나머지(rules[1:])는 DetailListView의 접힌 "그 외 판정된 규칙" 목록에 나열한다.
export interface Rule {
  riskLevel: RiskLevel;
  category: FindingCategory;
  message: string;
  officialFunction: string | null;
  // 백엔드 근거 인용 기능이 아직 모든 경로(목업 포함)에 반영되지 않아 optional로 둔다.
  sources?: FindingSource[];
}

export interface FindingResponse {
  sourceText: string;
  selector: string | null;
  // 항상 1개 이상 — rules[0]이 대표 규칙(메인 카드 콘텐츠), rules[1:]는 추가로 판정된 규칙.
  rules: Rule[];
}

export interface AnalysisResponse {
  analysisId: number;
  status: AnalysisStatus;
  summary: AnalysisSummary;
  findings: FindingResponse[];
}
