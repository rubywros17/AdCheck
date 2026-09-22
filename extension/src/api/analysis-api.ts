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

export async function getAnalysis(analysisId: number): Promise<AnalysisResponse> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}/api/v1/analyses/${analysisId}`, { method: "GET" });
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
          : "분석 결과를 조회할 수 없습니다.",
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

// Backend는 status에 따라 summary/findings의 유무가 다르다: PENDING/PROCESSING/FAILED는
// 분석이 아직 끝나지 않았거나(또는 실패했거나) summary를 계산할 결과가 없어 summary=null,
// findings=[]로 내려오고, COMPLETED일 때만 summary/findings가 실제 값으로 채워진다.
// (FAILED에 향후 errorMessage 필드가 추가되더라도 여기서는 검증하지 않고 있어도/없어도 유효로 둔다.)
function isAnalysisResponse(value: unknown): value is AnalysisResponse {
  if (!isRecord(value) || typeof value.analysisId !== "number" || !isAnalysisStatus(value.status)) {
    return false;
  }

  switch (value.status) {
    case "PENDING":
    case "PROCESSING":
    case "FAILED":
      return true;
    case "COMPLETED":
      return (
        isRecord(value.summary) &&
        typeof value.summary.findingCount === "number" &&
        typeof value.summary.officialFunctionMatchedCount === "number" &&
        Array.isArray(value.findings) &&
        value.findings.every(isFinding)
      );
  }
}

// riskLevel은 닫힌 3값 유니온이라 정확히 검증하고, category는 DB가 관리하는 Rule Engine
// 값이라 계속 늘어날 수 있으므로 특정 값으로 고정하지 않고 타입만 확인합니다. 클라이언트의
// 실제 화면 처리(색상/라벨)는 getCategoryTheme()의 fallback이 모르는 category도 안전하게
// 담당합니다.
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
    (typeof value.officialFunction === "string" || value.officialFunction === null) &&
    // 항목별 title/section/sourceUrl까지 전부 좁혀서 검증하지 않는다 — 바로 위 category/riskLevel
    // 사례처럼, 근거 인용 스키마가 앞으로 넓어질 때마다 여기서 또 전체 응답을 통째로 거부하는
    // 일을 반복하지 않기 위함. 배열인지만 확인한다.
    Array.isArray(value.sources)
  );
}

function isAnalysisStatus(value: unknown): value is AnalysisResponse["status"] {
  return value === "PENDING" || value === "PROCESSING" || value === "COMPLETED" || value === "FAILED";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
