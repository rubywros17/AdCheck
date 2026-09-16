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
  | "UNSUPPORTED";

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
  count: number;
  level: ReviewLevel;
}
