import { AnalysisApiError, createAnalysis, getAnalysis } from "../api/analysis-api";
import type { AnalysisResponse } from "../types/analysis";
import type {
  ActiveTabInfo,
  ActiveTabResult,
  AnalysisProgressMessage,
  AnalyzePageResult,
  ExtensionError,
  PageExtractionResult,
  SidePanelRequest,
} from "../types/message";

const POLL_INTERVAL_MS = 1_500;
const MAX_POLL_ATTEMPTS = 20; // 1.5초 * 20회 = 최대 30초 대기

void configureSidePanel();
chrome.runtime.onInstalled.addListener(() => {
  void configureSidePanel();
});

chrome.runtime.onMessage.addListener((message: unknown, _sender, sendResponse) => {
  if (!isSidePanelRequest(message)) {
    return false;
  }

  if (message.type === "GET_ACTIVE_TAB") {
    getActiveTabInfo().then(sendResponse);
  } else {
    analyzeCurrentPage().then(sendResponse);
  }
  return true;
});

async function configureSidePanel(): Promise<void> {
  try {
    await chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true });
  } catch (error) {
    console.error("[AdCheck] Failed to configure side panel behavior", error);
  }
}

async function getActiveTabInfo(): Promise<ActiveTabResult> {
  try {
    const tab = await getActiveTab();
    return {
      ok: true,
      data: {
        title: tab.title?.trim() || "제목을 확인할 수 없는 페이지",
        url: tab.url ?? "",
      },
    };
  } catch (error) {
    return { ok: false, error: normalizeError(error) };
  }
}

async function analyzeCurrentPage(): Promise<AnalyzePageResult> {
  try {
    await reportProgress("EXTRACTING");
    const tab = await getActiveTab();
    if (tab.id === undefined) {
      throw extensionError("NO_ACTIVE_TAB", "현재 활성 탭을 찾을 수 없습니다.");
    }

    await ensureContentScript(tab.id);
    const extraction: unknown = await chrome.tabs.sendMessage(tab.id, { type: "EXTRACT_PAGE" });
    if (!isPageExtractionResult(extraction)) {
      throw extensionError("EXTRACTION_FAILED", "현재 페이지의 분석 정보를 확인하지 못했습니다.");
    }
    if (!extraction.ok) {
      throw extraction.error;
    }
    if (!isSupportedWebUrl(extraction.data.pageUrl)) {
      throw restrictedPageError();
    }

    await reportProgress("ANALYZING");
    let analysis = await createAnalysis(extraction.data);
    if (analysis.status === "PENDING" || analysis.status === "PROCESSING") {
      analysis = await pollUntilFinished(analysis.analysisId);
    }
    return { ok: true, data: analysis };
  } catch (error) {
    console.error("[AdCheck] Page analysis failed", error);
    return { ok: false, error: normalizeError(error) };
  }
}

async function pollUntilFinished(analysisId: number): Promise<AnalysisResponse> {
  for (let attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
    await sleep(POLL_INTERVAL_MS);
    const result = await getAnalysis(analysisId);
    if (result.status === "COMPLETED" || result.status === "FAILED") {
      return result;
    }
  }
  throw extensionError("ANALYSIS_TIMEOUT", "분석이 너무 오래 걸리고 있어요. 잠시 후 다시 시도해주세요.");
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function getActiveTab(): Promise<chrome.tabs.Tab> {
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  if (!tab) {
    throw extensionError("NO_ACTIVE_TAB", "현재 활성 탭을 찾을 수 없습니다.");
  }
  if (tab.url && !isSupportedWebUrl(tab.url)) {
    throw restrictedPageError();
  }
  return tab;
}

function isSupportedWebUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

function restrictedPageError(): ExtensionError {
  return extensionError(
    "RESTRICTED_PAGE",
    "이 페이지에서는 분석을 실행할 수 없습니다. 일반 웹 상품페이지에서 다시 시도해주세요.",
  );
}

async function ensureContentScript(tabId: number): Promise<void> {
  try {
    const ping: unknown = await chrome.tabs.sendMessage(tabId, { type: "PING_CONTENT_SCRIPT" });
    if (isSuccessfulPing(ping)) {
      return;
    }
  } catch {
    // The script has not been injected into this page yet.
  }

  try {
    await chrome.scripting.executeScript({
      target: { tabId },
      files: ["assets/content-script.js"],
    });
  } catch (cause) {
    throw new Error("Content script injection failed", {
      cause: extensionError(
        "CONTENT_SCRIPT_UNAVAILABLE",
        "Chrome에서 접근할 수 없는 페이지입니다. 일반 웹 상품페이지에서 다시 시도해주세요.",
      ),
    });
  }
}

async function reportProgress(stage: AnalysisProgressMessage["stage"]): Promise<void> {
  const message: AnalysisProgressMessage = { type: "ANALYSIS_PROGRESS", stage };
  try {
    await chrome.runtime.sendMessage(message);
  } catch {
    // The side panel may have been closed while an analysis was running.
  }
}

function normalizeError(error: unknown): ExtensionError {
  if (error instanceof AnalysisApiError) {
    return error.detail;
  }
  if (isExtensionError(error)) {
    return error;
  }
  if (error instanceof Error && isExtensionError(error.cause)) {
    return error.cause;
  }
  return {
    code: "UNKNOWN_ERROR",
    message: "페이지 분석 중 예상하지 못한 오류가 발생했습니다.",
  };
}

function extensionError(code: ExtensionError["code"], message: string): ExtensionError {
  return { code, message };
}

function isSidePanelRequest(value: unknown): value is SidePanelRequest {
  return (
    typeof value === "object" &&
    value !== null &&
    "type" in value &&
    (value.type === "ANALYZE_CURRENT_PAGE" || value.type === "GET_ACTIVE_TAB")
  );
}

function isSuccessfulPing(value: unknown): boolean {
  return typeof value === "object" && value !== null && "ok" in value && value.ok === true;
}

function isExtensionError(value: unknown): value is ExtensionError {
  return (
    typeof value === "object" &&
    value !== null &&
    "code" in value &&
    typeof value.code === "string" &&
    "message" in value &&
    typeof value.message === "string"
  );
}

function isPageExtractionResult(value: unknown): value is PageExtractionResult {
  if (typeof value !== "object" || value === null || !("ok" in value)) {
    return false;
  }
  if (value.ok === false) {
    return "error" in value && isExtensionError(value.error);
  }
  return value.ok === true && "data" in value && typeof value.data === "object" && value.data !== null;
}
