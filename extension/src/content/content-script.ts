import { EXTRACTION_BUILD } from "./marketplace-detail";
import type { ContentScriptRequest, PageExtractionResult } from "../types/message";
import { extractPageEvidence, extractMarketplaceFrameEvidence } from "./page-extractor";

chrome.runtime.onMessage.addListener((message: unknown, _sender, sendResponse) => {
  if (!isContentScriptRequest(message)) {
    return false;
  }

  if (message.type === "PING_CONTENT_SCRIPT") {
    sendResponse({ ok: true, build: EXTRACTION_BUILD });
    return false;
  }

  if (message.type === "EXTRACT_MARKETPLACE_FRAME" && (window === window.top || message.url !== location.href)) {
    sendResponse({ ok: false, error: { code: "EXTRACTION_FAILED", message: "상세 문서가 변경되었습니다." } });
    return false;
  }
  void (message.type === "EXTRACT_MARKETPLACE_FRAME" ? extractMarketplaceFrameEvidence() : extractPageEvidence())
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
          message: error instanceof Error ? error.message : "현재 페이지 정보를 확인하지 못했습니다.",
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
  return value.type === "PING_CONTENT_SCRIPT" || value.type === "EXTRACT_PAGE" || (value.type === "EXTRACT_MARKETPLACE_FRAME" && "url" in value && typeof value.url === "string");
}
