import { API_BASE_URL } from "../config/env";
import type { AnalysisResponse, CreateAnalysisRequest } from "../types/analysis";
import type { ExtensionError, ExtensionErrorCode } from "../types/message";

export class AnalysisApiError extends Error {
  constructor(
    public readonly detail: ExtensionError,
    options?: ErrorOptions,
  ) {
    super(detail.message, options);
    this.name = "AnalysisApiError";
  }
}

export async function createAnalysis(request: CreateAnalysisRequest): Promise<AnalysisResponse> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}/api/v1/analyses`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });
  } catch (cause) {
    throw new AnalysisApiError(
      {
        code: "BACKEND_UNAVAILABLE",
        message: "AdCheck 서버에 연결할 수 없습니다. Backend 실행 상태를 확인해주세요.",
      },
      { cause },
    );
  }

  if (!response.ok) {
    throw new AnalysisApiError({
      code: classifyHttpError(response.status),
      message:
        response.status >= 500
          ? "분석 서버에서 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
          : "현재 페이지 정보를 분석 요청으로 보낼 수 없습니다.",
    });
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch (cause) {
    throw invalidResponseError(cause);
  }

  if (!isAnalysisResponse(payload)) {
    throw invalidResponseError();
  }
  return payload;
}

function classifyHttpError(status: number): ExtensionErrorCode {
  return status >= 500 ? "BACKEND_SERVER_ERROR" : "BACKEND_CLIENT_ERROR";
}

function invalidResponseError(cause?: unknown): AnalysisApiError {
  return new AnalysisApiError(
    {
      code: "INVALID_BACKEND_RESPONSE",
      message: "분석 서버의 응답 형식을 확인할 수 없습니다.",
    },
    { cause },
  );
}

function isAnalysisResponse(value: unknown): value is AnalysisResponse {
  if (!isRecord(value) || !isRecord(value.summary) || !Array.isArray(value.findings)) {
    return false;
  }

  return (
    typeof value.analysisId === "number" &&
    isAnalysisStatus(value.status) &&
    typeof value.summary.findingCount === "number" &&
    typeof value.summary.officialFunctionMatchedCount === "number" &&
    value.findings.every(isFinding)
  );
}

const RISK_LEVELS = new Set(["HIGH", "CAUTION", "NORMAL"]);

function isFinding(value: unknown): boolean {
  return (
    isRecord(value) &&
    typeof value.sourceText === "string" &&
    (typeof value.selector === "string" || value.selector === null) &&
    typeof value.riskLevel === "string" &&
    RISK_LEVELS.has(value.riskLevel) &&
    // category는 Rule Engine의 judgmentCategory 문자열(71종)을 그대로 통과시키는 열린 값이라
    // 특정 값으로 좁히지 않는다 — 예전엔 "FUNCTION_CLAIM" 하나만 통과시켜서, Finding 하나라도
    // 다른 카테고리(또는 HIGH/NORMAL 위험도)면 isAnalysisResponse 전체가 실패해 정상적인 분석
    // 결과까지 "분석할 수 없어요" 에러로 통째로 버려지고 있었다.
    typeof value.category === "string" &&
    value.category.length > 0 &&
    typeof value.message === "string" &&
    (typeof value.officialFunction === "string" || value.officialFunction === null)
  );
}

function isAnalysisStatus(value: unknown): value is AnalysisResponse["status"] {
  return value === "PENDING" || value === "PROCESSING" || value === "COMPLETED" || value === "FAILED";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
