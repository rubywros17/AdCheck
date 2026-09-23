import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import {
  CURRENT_PAGE_TITLE, CURRENT_PAGE_URL, DEFAULT_SCAN_HISTORIES,
  MAX_SCAN_HISTORY_COUNT, MOCK_FINDINGS, SCAN_CYCLE_MS, SCAN_HISTORY_STORAGE_KEY,
} from "../data";
import type { FilterCategory, FindingWithKeyword, ReviewLevel, ScanHistoryItem, TestTarget, ViewStatus } from "../types";
import { getCategoryTheme } from "../../constants/judgmentCategories";
import type { AnalysisResponse, FindingResponse } from "../../types/analysis";
import type { ActiveTabResult, AnalyzePageResult } from "../../types/message";
import { getAnalysis } from "../../api/analysis-api";

function isScanHistoryItem(value: unknown): value is ScanHistoryItem {
  if (typeof value !== "object" || value === null) return false;
  const item = value as Record<string, unknown>;
  return typeof item.id === "string"
    && typeof item.dateStr === "string"
    && typeof item.productName === "string"
    && typeof item.pageUrl === "string"
    && typeof item.count === "number"
    && Number.isInteger(item.count)
    && item.count >= 0
    && item.count <= MOCK_FINDINGS.length
    && item.level === getReviewLevel(item.count)
    && (item.favorite === undefined || typeof item.favorite === "boolean")
    && typeof item.analysisId === "number";
}

// extraction-test-recorder.ts와 동일한 chrome.storage.local 패턴 — 사이드패널을 닫았다 다시
// 열어도(문서가 새로 만들어져 sessionStorage가 비워져도) 점검 기록이 유지되도록 한다.
async function loadScanHistories(): Promise<ScanHistoryItem[]> {
  try {
    const stored = await chrome.storage.local.get(SCAN_HISTORY_STORAGE_KEY);
    const parsed: unknown = stored[SCAN_HISTORY_STORAGE_KEY];
    if (parsed === undefined) return DEFAULT_SCAN_HISTORIES;
    return Array.isArray(parsed) ? parsed.filter(isScanHistoryItem) : DEFAULT_SCAN_HISTORIES;
  } catch {
    return DEFAULT_SCAN_HISTORIES;
  }
}

async function saveScanHistories(histories: ScanHistoryItem[]): Promise<void> {
  try {
    await chrome.storage.local.set({ [SCAN_HISTORY_STORAGE_KEY]: histories });
  } catch {
    // The sidepanel remains usable when chrome.storage is unavailable.
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
  // chrome.storage.local 로드는 비동기라 빈 배열로 시작한다 — 로드 완료 전에는 기존 UI의
  // "저장된 점검 기록이 없어요" 빈 상태 그대로 보여주고, 로드 끝나면 채운다(깜빡임 없음).
  const [scanHistories, setScanHistories] = useState<ScanHistoryItem[]>([]);
  const [historiesLoaded, setHistoriesLoaded] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [viewingHistory, setViewingHistory] = useState<ScanHistoryItem | null>(null);
  const [activeFilter, setActiveFilter] = useState<FilterCategory>("ALL");
  const [expandedFindings, setExpandedFindings] = useState<Set<number>>(new Set());
  const [pendingScrollIdx, setPendingScrollIdx] = useState<number | null>(null);
  const scanTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const analysisRequestIdRef = useRef(0);
  const [liveFindings, setLiveFindings] = useState<FindingWithKeyword[] | null>(null);
  const [livePageInfo, setLivePageInfo] = useState<{ title: string; url: string } | null>(null);

  // 점검 기록을 볼 때도(성공적으로 불러온 뒤) 방금 분석을 마쳤을 때와 마찬가지로 liveFindings에
  // 실제 결과가 채워진다 — 더 이상 MOCK_FINDINGS를 개수만 맞춰 흉내내지 않는다.
  const findings = liveFindings ?? MOCK_FINDINGS.slice(0, testTarget === "SAFE" ? 0 : MOCK_FINDINGS.length);
  const targetCount = viewingHistory?.count ?? findings.length;
  const currentPageTitle = viewingHistory?.productName ?? livePageInfo?.title ?? CURRENT_PAGE_TITLE;
  const currentPageUrl = viewingHistory?.pageUrl ?? livePageInfo?.url ?? CURRENT_PAGE_URL;
  const level = getReviewLevel(targetCount);
  // 점검 기록에서 선택해 보는 중이면 그 기록의 id, 방금 분석을 마친 화면이면 가장 최근에 추가된 기록(맨 앞)의 id
  const currentHistoryId = viewingHistory?.id ?? scanHistories[0]?.id;
  const isCurrentFavorite = scanHistories.find((history) => history.id === currentHistoryId)?.favorite ?? false;

  useEffect(() => {
    let cancelled = false;
    void loadScanHistories().then((histories) => {
      if (!cancelled) {
        setScanHistories(histories);
        setHistoriesLoaded(true);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    // 초기 로드가 끝나기 전(scanHistories가 아직 빈 배열)에 저장하면 실제 저장된 기록을
    // 빈 배열로 덮어써버리므로, 로드가 끝난 뒤의 변경만 저장한다.
    if (!historiesLoaded) return;
    void saveScanHistories(scanHistories);
  }, [scanHistories, historiesLoaded]);

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

  function pushHistory(count: number, productName: string, pageUrl: string, analysisId: number) {
    const history: ScanHistoryItem = {
      id: crypto.randomUUID(),
      dateStr: "방금 전",
      productName,
      pageUrl,
      count,
      level: getReviewLevel(count),
      analysisId,
    };
    // 최신순으로 맨 앞에 추가하므로, 오래된 것을 버리려면 뒤쪽(끝)을 잘라내면 된다.
    setScanHistories((previous) => [history, ...previous].slice(0, MAX_SCAN_HISTORY_COUNT));
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
        // 개발용 테스트 스위치 경로는 실제 Backend에 분석을 만들지 않으므로 조회 가능한
        // analysisId가 없다 — 존재할 수 없는 음수 placeholder를 넣어, 이 기록을 나중에 클릭하면
        // 정직하게 "만료됨" 안내(HISTORY_ERROR)로 이어지게 한다.
        pushHistory(count, CURRENT_PAGE_TITLE, CURRENT_PAGE_URL, -1);
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
      const pageUrl = tabResult.ok ? tabResult.data.url : CURRENT_PAGE_URL;
      setLiveFindings(mapped);
      setLivePageInfo(tabResult.ok ? tabResult.data : null);
      setStatus(mapped.length === 0 ? "EMPTY" : "SUMMARY_HERO");
      pushHistory(mapped.length, pageTitle, pageUrl, response.analysisId);
    } catch {
      if (analysisRequestIdRef.current === requestId) setStatus("ERROR");
    }
  }

  function changeTestTarget(target: TestTarget) {
    setTestTarget(target);
    goHome();
  }

  async function selectHistory(history: ScanHistoryItem) {
    cancelAnalysis();
    resetDetails();
    setIsHistoryOpen(false);
    setViewingHistory(history);
    setLiveFindings(null);
    setStatus("HISTORY_LOADING");

    const requestId = ++analysisRequestIdRef.current;
    try {
      const response = await getAnalysis(history.analysisId);
      if (analysisRequestIdRef.current !== requestId) return; // 그 사이 취소/재시작된 요청이면 무시

      // 재사용 TTL이 지나 DB에서 이미 정리됐거나(404 등 → catch로 감), 아직 COMPLETED가
      // 아닌 상태(정상 흐름에선 나올 일이 없지만 방어적으로)도 전부 같은 안내로 처리한다.
      if (response.status !== "COMPLETED") {
        setStatus("HISTORY_ERROR");
        return;
      }

      setLiveFindings(response.findings.map(toFindingWithKeyword));
      setStatus(response.findings.length === 0 ? "EMPTY" : "DETAIL_LIST");
    } catch {
      if (analysisRequestIdRef.current === requestId) setStatus("HISTORY_ERROR");
    }
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

  // 필터 칩 클릭 핸들러: 다른 필터로 전환할 때만 펼침 상태를 초기화하고,
  // 이미 선택된 필터를 다시 눌렀을 때는 펼쳐둔 항목을 그대로 유지합니다.
  function changeFilter(filter: FilterCategory) {
    if (filter !== activeFilter) {
      setExpandedFindings(new Set());
    }
    setActiveFilter(filter);
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

  function toggleFavorite(id: string) {
    setScanHistories((previous) =>
      previous.map((history) => (history.id === id ? { ...history, favorite: !history.favorite } : history)),
    );
  }

  return {
    testTarget, scanHistories, isHistoryOpen, activeFilter, expandedFindings,
    pendingScrollIdx, targetCount, currentPageTitle, findings, level,
    currentHistoryId, isCurrentFavorite,
    pageUrl: currentPageUrl,
    showHistory: status !== "ANALYZING",
    goHome, analyze, changeTestTarget, selectHistory, selectBubble, showAllFindings,
    toggleFinding, completeScroll, shareResults, locateFinding, toggleFavorite,
    setActiveFilter: changeFilter,
    openHistory: () => setIsHistoryOpen(true),
    closeHistory: () => setIsHistoryOpen(false),
  };
}
