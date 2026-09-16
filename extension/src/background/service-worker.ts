import { AnalysisApiError, createAnalysis } from "../api/analysis-api";
import {
  clearExtractionTestRecords,
  exportExtractionTestRecords,
  getExtractionTestRecords,
  recordExtractionTest,
} from "./extraction-test-recorder";
import type {
  ActiveTabInfo,
  ActiveTabResult,
  AnalysisProgressMessage,
  AnalyzePageResult,
  BackgroundRequest,
  ExtensionError,
  PageExtractionResult,
} from "../types/message";

void configureSidePanel();
chrome.runtime.onInstalled.addListener(() => {
  void configureSidePanel();
});

chrome.commands.onCommand.addListener((command) => {
  if (command === "export-extraction-test-results") {
    void exportExtractionTestRecords()
      .then((recordCount) => {
        console.info(`[AdCheck] Exported ${recordCount} extraction test record(s)`);
      })
      .catch((error) => {
        console.error("[AdCheck] Failed to export extraction test records", error);
      });
  }
});

chrome.runtime.onMessage.addListener((message: unknown, _sender, sendResponse) => {
  if (!isBackgroundRequest(message)) {
    return false;
  }

  if (message.type === "GET_ACTIVE_TAB") {
    getActiveTabInfo().then(sendResponse);
  } else if (message.type === "GET_EXTRACTION_TEST_RECORDS") {
    getExtractionTestRecords().then((data) => sendResponse({ ok: true, data }));
  } else if (message.type === "EXPORT_EXTRACTION_TEST_RECORDS") {
    exportExtractionTestRecords().then((count) => sendResponse({ ok: true, count }));
  } else if (message.type === "CLEAR_EXTRACTION_TEST_RECORDS") {
    clearExtractionTestRecords().then(() => sendResponse({ ok: true }));
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

    console.info("[AdCheck] Starting page extraction", {
      tabId: tab.id,
      url: tab.url,
    });

    const extractionStartedAt = performance.now();
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

    try {
      await recordExtractionTest(extraction.data, performance.now() - extractionStartedAt);
    } catch (error) {
      // Test recording is auxiliary and must never prevent the normal analysis flow.
      console.error("[AdCheck] Failed to record extraction test result", error);
    }

    console.info("[AdCheck] Page evidence extracted", {
      pageUrl: extraction.data.pageUrl,
      productName: extraction.data.productName,
      textCount: extraction.data.texts.length,
      imageCount: extraction.data.images.length,
      evidence: extraction.data,
    });

    await reportProgress("ANALYZING");
    const analysis = await createAnalysis(extraction.data);
    return { ok: true, data: analysis };
  } catch (error) {
    console.error("[AdCheck] Page analysis failed", error);
    return { ok: false, error: normalizeError(error) };
  }
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
    console.error("[AdCheck] Content script injection failed", { tabId, cause });
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

function isBackgroundRequest(value: unknown): value is BackgroundRequest {
  return (
    typeof value === "object" &&
    value !== null &&
    "type" in value &&
    (value.type === "ANALYZE_CURRENT_PAGE" ||
      value.type === "GET_ACTIVE_TAB" ||
      value.type === "GET_EXTRACTION_TEST_RECORDS" ||
      value.type === "EXPORT_EXTRACTION_TEST_RECORDS" ||
      value.type === "CLEAR_EXTRACTION_TEST_RECORDS")
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
