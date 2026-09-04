import { useEffect, useState } from "react";
import type { AnalysisResponse, FindingResponse } from "../types/analysis";
import type {
  ActiveTabInfo,
  ActiveTabResult,
  AnalysisProgressMessage,
  AnalyzePageResult,
  ExtensionError,
} from "../types/message";

type ViewStatus = "IDLE" | "EXTRACTING" | "ANALYZING" | "SUCCESS" | "ERROR";
const LOGO_URL = chrome.runtime.getURL("icons/adcheck_logo.png");

export function App() {
  const [status, setStatus] = useState<ViewStatus>("IDLE");
  const [activeTab, setActiveTab] = useState<ActiveTabInfo | null>(null);
  const [result, setResult] = useState<AnalyzePageResult | null>(null);

  useEffect(() => {
    void loadActiveTab();

    function handleProgress(message: unknown) {
      if (!isProgressMessage(message)) {
        return;
      }
      setStatus(message.stage);
    }

    chrome.runtime.onMessage.addListener(handleProgress);
    return () => chrome.runtime.onMessage.removeListener(handleProgress);
  }, []);

  async function loadActiveTab() {
    try {
      const response: unknown = await chrome.runtime.sendMessage({ type: "GET_ACTIVE_TAB" });
      if (isActiveTabResult(response) && response.ok) {
        setActiveTab(response.data);
      }
    } catch (error) {
      console.error("[AdCheck] Failed to read the active tab", error);
    }
  }

  async function analyzeCurrentPage() {
    setResult(null);
    setStatus("EXTRACTING");
    try {
      const response: unknown = await chrome.runtime.sendMessage({ type: "ANALYZE_CURRENT_PAGE" });
      if (!isAnalyzePageResult(response)) {
        setResult({
          ok: false,
          error: {
            code: "UNKNOWN_ERROR",
            message: "확인할 수 없는 응답을 받았습니다. 다시 시도해주세요.",
          },
        });
        setStatus("ERROR");
        return;
      }
      setResult(response);
      setStatus(response.ok ? "SUCCESS" : "ERROR");
      await loadActiveTab();
    } catch (error) {
      console.error("[AdCheck] Service worker messaging failed", error);
      setResult({
        ok: false,
        error: {
          code: "CONTENT_SCRIPT_UNAVAILABLE",
          message: "확장 프로그램과 통신하지 못했습니다. 확장 프로그램을 다시 열어주세요.",
        },
      });
      setStatus("ERROR");
    }
  }

  const isLoading = status === "EXTRACTING" || status === "ANALYZING";
  const analysis = result?.ok ? result.data : null;
  const error = result && !result.ok ? result.error : null;

  return (
    <main className="app-shell">
      <header className="brand-header">
        <div>
          <h1><img className="brand-logo" src={LOGO_URL} alt="AdCheck" /></h1>
          <p>건강기능식품 광고,<br />구매 전에 확인해보세요.</p>
        </div>
      </header>

      <section className="page-card" aria-label="현재 페이지">
        <span className="eyebrow">현재 페이지</span>
        <strong>{activeTab?.title ?? "현재 페이지를 확인하고 있어요..."}</strong>
        {activeTab?.url && <span className="page-url">{activeTab.url}</span>}
      </section>

      <button className="analyze-button" type="button" disabled={isLoading} onClick={analyzeCurrentPage}>
        {isLoading ? "분석 중..." : "현재 페이지 분석"}
      </button>

      <StatusContent status={status} analysis={analysis} error={error} />

      <footer>
        AdCheck는 의료적 효능이나 법률적 위반 여부를 확정하지 않습니다.
      </footer>
    </main>
  );
}

function StatusContent({
  status,
  analysis,
  error,
}: {
  status: ViewStatus;
  analysis: AnalysisResponse | null;
  error: ExtensionError | null;
}) {
  if (status === "EXTRACTING") {
    return <Notice title="페이지 확인 중" message="상품페이지 정보를 확인하고 있어요..." loading />;
  }
  if (status === "ANALYZING") {
    return <Notice title="표현 분석 중" message="광고 표현을 분석하고 있어요..." loading />;
  }
  if (status === "ERROR") {
    return <Notice title="분석할 수 없어요" message={error?.message ?? "잠시 후 다시 시도해주세요."} tone="error" />;
  }
  if (status === "SUCCESS" && analysis) {
    return (
      <section className="results" aria-live="polite">
        <div className="result-heading">
          <div>
            <span className="eyebrow">AdCheck 분석 결과</span>
            <h2>확인이 필요한 표현</h2>
          </div>
          <span className="finding-count">{analysis.summary.findingCount}개</span>
        </div>

        {analysis.findings.length === 0 ? (
          <div className="empty-result">
            <span className="empty-icon" aria-hidden="true">✓</span>
            <p>현재 분석 범위에서<br />확인이 필요한 표현을 찾지 못했습니다.</p>
          </div>
        ) : (
          <div className="finding-list">
            {analysis.findings.map((finding, index) => (
              <FindingCard key={`${finding.selector ?? "finding"}-${index}`} finding={finding} />
            ))}
          </div>
        )}
      </section>
    );
  }
  return <Notice title="분석 준비 완료" message="현재 상품페이지를 분석해보세요." />;
}

function FindingCard({ finding }: { finding: FindingResponse }) {
  return (
    <article className="finding-card">
      <span className="risk-badge">확인 필요 · {finding.riskLevel}</span>
      <blockquote>“{finding.sourceText}”</blockquote>
      <p className="finding-message">{finding.message}</p>
      {finding.officialFunction && (
        <div className="official-function">
          <span>공식 기능성</span>
          <p>{finding.officialFunction}</p>
        </div>
      )}
    </article>
  );
}

function Notice({
  title,
  message,
  loading = false,
  tone = "default",
}: {
  title: string;
  message: string;
  loading?: boolean;
  tone?: "default" | "error";
}) {
  return (
    <section className={`notice ${tone === "error" ? "notice-error" : ""}`} aria-live="polite">
      {loading && <span className="loader" aria-hidden="true" />}
      <div>
        <strong>{title}</strong>
        <p>{message}</p>
      </div>
    </section>
  );
}

function isProgressMessage(value: unknown): value is AnalysisProgressMessage {
  return (
    typeof value === "object" &&
    value !== null &&
    "type" in value &&
    value.type === "ANALYSIS_PROGRESS" &&
    "stage" in value &&
    (value.stage === "EXTRACTING" || value.stage === "ANALYZING")
  );
}

function isActiveTabResult(value: unknown): value is ActiveTabResult {
  return typeof value === "object" && value !== null && "ok" in value;
}

function isAnalyzePageResult(value: unknown): value is AnalyzePageResult {
  return typeof value === "object" && value !== null && "ok" in value;
}
