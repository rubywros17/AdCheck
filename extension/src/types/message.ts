import type { AnalysisResponse } from "./analysis";
import type { PageEvidence } from "./evidence";

export type AnalysisProgressStage = "EXTRACTING" | "ANALYZING";

export interface AnalyzeCurrentPageMessage {
  type: "ANALYZE_CURRENT_PAGE";
}

export interface GetActiveTabMessage {
  type: "GET_ACTIVE_TAB";
}

export interface AnalysisProgressMessage {
  type: "ANALYSIS_PROGRESS";
  stage: AnalysisProgressStage;
}

export interface PingContentScriptMessage {
  type: "PING_CONTENT_SCRIPT";
}

export interface ExtractPageMessage {
  type: "EXTRACT_PAGE";
}

export interface GetExtractionTestRecordsMessage {
  type: "GET_EXTRACTION_TEST_RECORDS";
}

export interface ExportExtractionTestRecordsMessage {
  type: "EXPORT_EXTRACTION_TEST_RECORDS";
}

export interface ClearExtractionTestRecordsMessage {
  type: "CLEAR_EXTRACTION_TEST_RECORDS";
}

export type SidePanelRequest = AnalyzeCurrentPageMessage | GetActiveTabMessage;
export type TestRecorderRequest =
  | GetExtractionTestRecordsMessage
  | ExportExtractionTestRecordsMessage
  | ClearExtractionTestRecordsMessage;
export type BackgroundRequest = SidePanelRequest | TestRecorderRequest;
export type ContentScriptRequest = PingContentScriptMessage | ExtractPageMessage | { type: "EXTRACT_MARKETPLACE_FRAME"; url: string };

export type ExtensionErrorCode =
  | "NO_ACTIVE_TAB"
  | "RESTRICTED_PAGE"
  | "EXTRACTION_FAILED"
  | "CONTENT_SCRIPT_UNAVAILABLE"
  | "BACKEND_UNAVAILABLE"
  | "BACKEND_CLIENT_ERROR"
  | "BACKEND_SERVER_ERROR"
  | "INVALID_BACKEND_RESPONSE"
  | "UNKNOWN_ERROR";

export interface ExtensionError {
  code: ExtensionErrorCode;
  message: string;
}

export interface ActiveTabInfo {
  title: string;
  url: string;
}

export type AnalyzePageResult =
  | { ok: true; data: AnalysisResponse }
  | { ok: false; error: ExtensionError };

export type ActiveTabResult =
  | { ok: true; data: ActiveTabInfo }
  | { ok: false; error: ExtensionError };

export type PageExtractionResult =
  | { ok: true; data: PageEvidence }
  | { ok: false; error: ExtensionError };
