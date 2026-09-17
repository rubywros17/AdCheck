import type { ContentScriptRequest, PageExtractionResult } from "../types/message";
import { extractPageEvidence } from "./page-extractor";

chrome.runtime.onMessage.addListener((message: unknown, _sender, sendResponse) => {
  if (!isContentScriptRequest(message)) {
    return false;
  }

  if (message.type === "PING_CONTENT_SCRIPT") {
    sendResponse({ ok: true });
    return false;
  }

  void extractPageEvidence()
    .then((data) => {
      const result: PageExtractionResult = { ok: true, data };
      sendResponse(result);
    })
    .catch((error: unknown) => {
      console.error("[AdCheck] DOM evidence extraction failed", error);
      const result: PageExtractionResult = {
        ok: false,
        error: {
          code: "EXTRACTION_FAILED",
          message: "현재 페이지 정보를 확인하지 못했습니다.",
        },
      };
      sendResponse(result);
    });

  return true;
});

function isContentScriptRequest(value: unknown): value is ContentScriptRequest {
  if (typeof value !== "object" || value === null || !("type" in value)) {
    return false;
  }
  return value.type === "PING_CONTENT_SCRIPT" || value.type === "EXTRACT_PAGE";
}
