import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import {
  CURRENT_PAGE_TITLE, CURRENT_PAGE_URL, DEFAULT_SCAN_HISTORIES,
  MOCK_FINDINGS, SCAN_CYCLE_MS, SCAN_HISTORY_STORAGE_KEY,
} from "../data";
import type { FilterCategory, FindingWithKeyword, ReviewLevel, ScanHistoryItem, TestTarget, ViewStatus } from "../types";

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

export function useAdCheck(status: ViewStatus, setStatus: Dispatch<SetStateAction<ViewStatus>>) {
  const [testTarget, setTestTarget] = useState<TestTarget>("NORMAL");
  const [scanHistories, setScanHistories] = useState(loadScanHistories);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [viewingHistory, setViewingHistory] = useState<ScanHistoryItem | null>(null);
  const [activeFilter, setActiveFilter] = useState<FilterCategory>("ALL");
  const [expandedFindings, setExpandedFindings] = useState<Set<number>>(new Set());
  const [pendingScrollIdx, setPendingScrollIdx] = useState<number | null>(null);
  const scanTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const targetCount = viewingHistory?.count ?? (testTarget === "SAFE" ? 0 : MOCK_FINDINGS.length);
  const currentPageTitle = viewingHistory?.productName ?? CURRENT_PAGE_TITLE;
  const findings = MOCK_FINDINGS.slice(0, targetCount);
  const level = getReviewLevel(targetCount);

  useEffect(() => {
    try {
      sessionStorage.setItem(SCAN_HISTORY_STORAGE_KEY, JSON.stringify(scanHistories));
    } catch {
      // The sidepanel remains usable when session storage is unavailable.
    }
  }, [scanHistories]);

  const cancelAnalysis = useCallback(() => {
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

  function analyze() {
    cancelAnalysis();
    resetDetails();
    setViewingHistory(null);
    setIsHistoryOpen(false);
    setStatus("ANALYZING");
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
      const history: ScanHistoryItem = {
        id: crypto.randomUUID(),
        dateStr: "방금 전",
        productName: CURRENT_PAGE_TITLE,
        count,
        level: getReviewLevel(count),
      };
      setScanHistories((previous) => [history, ...previous]);
    }, SCAN_CYCLE_MS);
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
    pageUrl: CURRENT_PAGE_URL,
    showHistory: !["ANALYZING", "SUMMARY_HERO", "EMPTY"].includes(status),
    goHome, analyze, changeTestTarget, selectHistory, selectBubble, showAllFindings,
    toggleFinding, completeScroll, shareResults, locateFinding,
    setActiveFilter,
    openHistory: () => setIsHistoryOpen(true),
    closeHistory: () => setIsHistoryOpen(false),
  };
}
