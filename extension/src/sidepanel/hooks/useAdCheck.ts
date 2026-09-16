import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import {
  CURRENT_PAGE_TITLE, CURRENT_PAGE_URL, DEFAULT_SCAN_HISTORIES,
  MOCK_FINDINGS, SCAN_CYCLE_MS, SCAN_HISTORY_STORAGE_KEY,
} from "../data";
import type { FilterCategory, FindingWithKeyword, ReviewLevel, ScanHistoryItem, TestTarget, ViewStatus } from "../types";
import { getCategoryTheme } from "../../constants/judgmentCategories";
import type { AnalysisResponse, FindingResponse } from "../../types/analysis";
import type { ActiveTabResult, AnalyzePageResult } from "../../types/message";

function isScanHistoryItem(value: unknown): value is ScanHistoryItem {
  if (typeof value !== "object" || value === null) return false;
  const item = value as Record<string, unknown>;
  return typeof item.id === "string"
    && typeof item.dateStr === "string"
    && typeof item.productName === "string"
    && typeof item.count === "number"
    && Number.isInteger(item.count)
    && item.count >= 0
    && item.count <= MOCK_FINDINGS.length
    && item.level === getReviewLevel(item.count);
}

function loadScanHistories(): ScanHistoryItem[] {
  try {
    const raw = sessionStorage.getItem(SCAN_HISTORY_STORAGE_KEY);
    if (!raw) return DEFAULT_SCAN_HISTORIES;
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter(isScanHistoryItem) : DEFAULT_SCAN_HISTORIES;
  } catch {
    return DEFAULT_SCAN_HISTORIES;
  }
}

function getReviewLevel(count: number): ReviewLevel {
  if (count === 0) return "SAFE";
  return count <= 3 ? "CAUTION" : "REVIEW";
}

function toFindingWithKeyword(finding: FindingResponse): FindingWithKeyword {
  return {
    ...finding,
    keyword: finding.sourceText,
    bubbleLabel: getCategoryTheme(finding.category).label,
  };
}

export function useAdCheck(status: ViewStatus, setStatus: Dispatch<SetStateAction<ViewStatus>>) {
  const [testTarget, setTestTarget] = useState<TestTarget>("NORMAL");
  const [scanHistories, setScanHistories] = useState(loadScanHistories);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [viewingHistory, setViewingHistory] = useState<ScanHistoryItem | null>(null);
  const [activeFilter, setActiveFilter] = useState<FilterCategory>("ALL");
  const [expandedFindings, setExpandedFindings] = useState<Set<number>>(new Set());
  const [pendingScrollIdx, setPendingScrollIdx] = useState<number | null>(null);
  const scanTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const analysisRequestIdRef = useRef(0);
  const [liveFindings, setLiveFindings] = useState<FindingWithKeyword[] | null>(null);
  const [livePageInfo, setLivePageInfo] = useState<{ title: string; url: string } | null>(null);

  const findings = viewingHistory
    ? MOCK_FINDINGS.slice(0, viewingHistory.count)
    : liveFindings ?? MOCK_FINDINGS.slice(0, testTarget === "SAFE" ? 0 : MOCK_FINDINGS.length);
  const targetCount = viewingHistory?.count ?? findings.length;
  const currentPageTitle = viewingHistory?.productName ?? livePageInfo?.title ?? CURRENT_PAGE_TITLE;
  const level = getReviewLevel(targetCount);

  useEffect(() => {
    try {
      sessionStorage.setItem(SCAN_HISTORY_STORAGE_KEY, JSON.stringify(scanHistories));
    } catch {
      // The sidepanel remains usable when session storage is unavailable.
    }
  }, [scanHistories]);

  const cancelAnalysis = useCallback(() => {
    analysisRequestIdRef.current += 1; // 진행 중이던 실제 응답을 stale 처리
    if (scanTimerRef.current !== null) {
      clearTimeout(scanTimerRef.current);
      scanTimerRef.current = null;
    }
  }, []);

  useEffect(() => cancelAnalysis, [cancelAnalysis]);

  const resetDetails = useCallback(() => {
    setActiveFilter("ALL");
    setExpandedFindings(new Set());
    setPendingScrollIdx(null);
  }, []);

  const goHome = useCallback(() => {
    cancelAnalysis();
    resetDetails();
    setViewingHistory(null);
    setIsHistoryOpen(false);
    setStatus("IDLE");
  }, [cancelAnalysis, resetDetails, setStatus]);

  function pushHistory(count: number, productName: string) {
    const history: ScanHistoryItem = {
      id: crypto.randomUUID(),
      dateStr: "방금 전",
      productName,
      count,
      level: getReviewLevel(count),
    };
    setScanHistories((previous) => [history, ...previous]);
  }

  async function analyze() {
    cancelAnalysis();
    resetDetails();
    setViewingHistory(null);
    setIsHistoryOpen(false);
    setStatus("ANALYZING");

    // 개발용 테스트 스위치(SAFE/ERROR/INVALID)는 실제 서버 없이 화면을 확인하기 위한
    // 기존 mock 경로를 그대로 유지한다. NORMAL일 때만 실제 Backend를 호출한다.
    if (testTarget !== "NORMAL") {
      const count = testTarget === "SAFE" ? 0 : MOCK_FINDINGS.length;
      scanTimerRef.current = setTimeout(() => {
        scanTimerRef.current = null;
        if (testTarget === "ERROR") {
          setStatus("ERROR");
          return;
        }
        if (testTarget === "INVALID") {
          setStatus("UNSUPPORTED");
          return;
        }
        setStatus(count === 0 ? "EMPTY" : "SUMMARY_HERO");
        pushHistory(count, CURRENT_PAGE_TITLE);
      }, SCAN_CYCLE_MS);
      return;
    }

    const requestId = ++analysisRequestIdRef.current;
    try {
      const [tabResult, analyzeResult] = await Promise.all([
        chrome.runtime.sendMessage({ type: "GET_ACTIVE_TAB" }) as Promise<ActiveTabResult>,
        chrome.runtime.sendMessage({ type: "ANALYZE_CURRENT_PAGE" }) as Promise<AnalyzePageResult>,
      ]);
      if (analysisRequestIdRef.current !== requestId) return; // 그 사이 취소/재시작된 요청이면 무시

      if (!analyzeResult.ok) {
        // createAnalysis() 실패(BACKEND_UNAVAILABLE 등)와 폴링 타임아웃(ANALYSIS_TIMEOUT) 모두
        // 여기로 들어오며, RESTRICTED_PAGE/CONTENT_SCRIPT_UNAVAILABLE만 별도로 UNSUPPORTED 처리한다.
        const unsupported = analyzeResult.error.code === "RESTRICTED_PAGE"
          || analyzeResult.error.code === "CONTENT_SCRIPT_UNAVAILABLE";
        setStatus(unsupported ? "UNSUPPORTED" : "ERROR");
        return;
      }

      const response: AnalysisResponse = analyzeResult.data;
      if (response.status === "FAILED") {
        setStatus("ERROR");
        return;
      }

      const mapped = response.findings.map(toFindingWithKeyword);
      const pageTitle = tabResult.ok ? tabResult.data.title : CURRENT_PAGE_TITLE;
      setLiveFindings(mapped);
      setLivePageInfo(tabResult.ok ? tabResult.data : null);
      setStatus(mapped.length === 0 ? "EMPTY" : "SUMMARY_HERO");
      pushHistory(mapped.length, pageTitle);
    } catch {
      if (analysisRequestIdRef.current === requestId) setStatus("ERROR");
    }
  }

  function changeTestTarget(target: TestTarget) {
    setTestTarget(target);
    goHome();
  }

  function selectHistory(history: ScanHistoryItem) {
    cancelAnalysis();
    resetDetails();
    setIsHistoryOpen(false);
    setViewingHistory(history);
    setStatus(history.level === "SAFE" ? "EMPTY" : "DETAIL_LIST");
  }

  function selectBubble(finding: FindingWithKeyword, idx: number) {
    setActiveFilter(finding.message.includes("의약품") ? "DISEASE" : "GUARANTEE");
    setExpandedFindings(new Set([idx]));
    setPendingScrollIdx(idx);
    setStatus("DETAIL_LIST");
  }

  function showAllFindings() {
    setActiveFilter("ALL");
    setPendingScrollIdx(null);
    setStatus("DETAIL_LIST");
  }

  function toggleFinding(idx: number) {
    setExpandedFindings((previous) => {
      const next = new Set(previous);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      return next;
    });
  }

  const completeScroll = useCallback(() => setPendingScrollIdx(null), []);

  async function shareResults() {
    const diseaseCount = findings.filter((finding) => finding.message.includes("의약품")).length;
    const guaranteeCount = findings.filter((finding) => finding.message.includes("과장")).length;
    const shareText = `[AdCheck 광고 검토 결과]\n총 ${targetCount}건 검토 필요 (오인 우려 표현 ${diseaseCount}건, 과장 표현 ${guaranteeCount}건)`;
    try {
      if (!navigator.clipboard?.writeText) {
        alert(shareText);
        return;
      }
      await navigator.clipboard.writeText(shareText);
      alert("검토 결과가 클립보드에 복사됐어요.");
    } catch {
      alert(shareText);
    }
  }

  function locateFinding(finding: FindingWithKeyword) {
    alert(`본문 내 위치: ${finding.selector}`);
  }

  return {
    testTarget, scanHistories, isHistoryOpen, activeFilter, expandedFindings,
    pendingScrollIdx, targetCount, currentPageTitle, findings, level,
    pageUrl: livePageInfo?.url ?? CURRENT_PAGE_URL,
    showHistory: !["ANALYZING", "SUMMARY_HERO", "EMPTY"].includes(status),
    goHome, analyze, changeTestTarget, selectHistory, selectBubble, showAllFindings,
    toggleFinding, completeScroll, shareResults, locateFinding,
    setActiveFilter,
    openHistory: () => setIsHistoryOpen(true),
    closeHistory: () => setIsHistoryOpen(false),
  };
}
