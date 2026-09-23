import type { FindingResponse } from "../types/analysis";

export type ViewStatus =
  | "SPLASH"
  | "IDLE"
  | "ANALYZING"
  | "SUMMARY_HERO"
  | "BUBBLE_PREVIEW"
  | "DETAIL_LIST"
  | "EMPTY"
  | "ERROR"
  | "UNSUPPORTED"
  | "HISTORY_LOADING"
  | "HISTORY_ERROR";

export type TestTarget = "NORMAL" | "SAFE" | "ERROR" | "INVALID";
export type FilterCategory = "ALL" | "DISEASE" | "GUARANTEE";
export type ReviewLevel = "SAFE" | "CAUTION" | "REVIEW";

export interface FindingWithKeyword extends FindingResponse {
  keyword: string;
  bubbleLabel: string;
}

export interface ScanHistoryItem {
  id: string;
  dateStr: string;
  productName: string;
  pageUrl: string;
  count: number;
  level: ReviewLevel;
  favorite?: boolean;
  // Backend analyses.id — 클릭 시 GET /api/v1/analyses/{analysisId}로 실제 결과를 다시 조회하는 데 쓴다.
  // (chrome.storage.local에 findings 전체를 저장하는 대신, 재사용 캐시가 만료되기 전까지는
  // 이 id 하나로 실제 결과를 다시 불러올 수 있다.)
  analysisId: number;
}
