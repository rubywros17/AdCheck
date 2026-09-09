import React, { useState, useEffect, useRef } from "react";
import type { FindingResponse } from "../types/analysis";

type ViewStatus = "SPLASH" | "IDLE" | "ANALYZING" | "SUMMARY_HERO" | "BUBBLE_PREVIEW" | "DETAIL_LIST" | "EMPTY" | "ERROR" | "UNSUPPORTED";
type TestTarget = "NORMAL" | "SAFE" | "ERROR" | "INVALID";
type FilterCategory = "ALL" | "DISEASE" | "GUARANTEE";

const SCAN_CYCLE_MS = 2400;
const SCAN_HISTORY_STORAGE_KEY = "adcheck_scan_histories";
// 지금 켜져 있는(방금 점검한) 페이지의 목업 이름 — 실제 탭 제목 추출이 연결되기 전까지 쓰는 자리표시자
const CURRENT_PAGE_TITLE = "프리미엄 눈 건강 루테인 지아잔틴 1000mg";

interface FindingWithKeyword extends FindingResponse {
  keyword: string;
  bubbleLabel: string;
}

interface ScanHistoryItem {
  id: string;
  dateStr: string;
  productName: string;
  count: number;
  level: "SAFE" | "CAUTION" | "REVIEW";
}

const DEFAULT_SCAN_HISTORIES: ScanHistoryItem[] = [
  {
    id: "h1",
    dateStr: "오늘 오후 10:20",
    productName: CURRENT_PAGE_TITLE,
    count: 10,
    level: "REVIEW",
  },
  {
    id: "h2",
    dateStr: "오늘 오후 6:05",
    productName: "초고함량 식물성 알티지 오메가3",
    count: 0,
    level: "SAFE",
  },
  {
    id: "h3",
    dateStr: "오늘 오후 3:15",
    productName: "관절엔 초록잎홍합 & MSM 콤플렉스",
    count: 2,
    level: "CAUTION",
  },
  {
    id: "h4",
    dateStr: "오늘 오전 11:40",
    productName: "간 건강 밀크씨슬 실리마린 800mg",
    count: 7,
    level: "REVIEW",
  },
  {
    id: "h5",
    dateStr: "오늘 오전 9:05",
    productName: "체지방 감소 가르시니아 컴플렉스",
    count: 1,
    level: "CAUTION",
  },
];

function loadScanHistories(): ScanHistoryItem[] {
  try {
    const raw = sessionStorage.getItem(SCAN_HISTORY_STORAGE_KEY);
    if (!raw) return DEFAULT_SCAN_HISTORIES;
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : DEFAULT_SCAN_HISTORIES;
  } catch {
    return DEFAULT_SCAN_HISTORIES;
  }
}

const MOCK_FINDINGS: FindingWithKeyword[] = [
  {
    keyword: "노안·백내장 근본 예방 및 시력 100% 완벽 회복 보장 특급 솔루션",
    bubbleLabel: "노안·백내장 예방",
    sourceText: "본 영양제는 단 2주일 만에 노안과 백내장을 근본적으로 예방하고 시력을 100% 완벽히 회복시켜 드립니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음",
    selector: "p.claim-1",
  },
  {
    keyword: "손상된 간세포 즉각 재생",
    bubbleLabel: "간세포 즉각 재생",
    sourceText: "잦은 음주로 극심하게 파괴된 간세포를 혁신적으로 즉각 재생시켜 줍니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "간 건강에 도움을 줄 수 있음",
    selector: "p.claim-2",
  },
  {
    keyword: "만성 관절염 완치",
    bubbleLabel: "관절염 완치",
    sourceText: "시큰거리는 퇴행성 관절염 통증을 며칠 만에 깨끗하게 완치 보장합니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "관절 및 연골건강에 도움을 줄 수 있음",
    selector: "p.claim-3",
  },
  {
    keyword: "혈관 핏떡 100% 융해",
    bubbleLabel: "혈전 100% 융해",
    sourceText: "혈액 속 뭉친 혈전과 핏떡을 100% 녹여내어 뇌졸중을 막아줍니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "혈중 중성지질 개선·혈행개선에 도움을 줄 수 있음",
    selector: "p.claim-4",
  },
  {
    keyword: "체지방 100% 완전 분해",
    bubbleLabel: "체지방 100% 완전 분해",
    sourceText: "운동이나 식단 조절 전혀 없이도 섭취된 탄수화물과 체지방을 100% 태웁니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "탄수화물이 지방으로 합성되는 것을 억제하여 체지방 감소에 도움을 줄 수 있음",
    selector: "p.claim-5",
  },
  {
    keyword: "기적의 활력 부스터",
    bubbleLabel: "피로 즉각 해소",
    sourceText: "먹자마자 3초 만에 만성 피로가 즉각 날아가는 기적의 에너지 폭탄",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "피로개선에 도움을 줄 수 있음",
    selector: "p.claim-6",
  },
  {
    keyword: "단 3일 7kg 감량 보장",
    bubbleLabel: "3일 7kg 감량 보장",
    sourceText: "임상 증명 완료! 3일간 섭취하면 무조건 체중 7kg 감량을 보장해 드립니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "체지방 감소에 도움을 줄 수 있음",
    selector: "p.claim-7",
  },
  {
    keyword: "일일 권장량 1000% 배합",
    bubbleLabel: "고단위 1000% 배합",
    sourceText: "시중 제품과는 차원이 다른 슈퍼 고단위 압축 배합으로 효과가 10배 뛰어납니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "영양소 보충 및 건강 증진에 도움을 줄 수 있음",
    selector: "p.claim-8",
  },
  {
    keyword: "전문의 만장일치 보증",
    bubbleLabel: "전문의 효과 보증",
    sourceText: "대한민국 최고 권위 전문의들이 직접 효과를 보증하고 만장일치로 추천한 제품",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "건강기능식품 공통 기준",
    selector: "p.claim-9",
  },
  {
    keyword: "초고속 면역력 급상승",
    bubbleLabel: "면역력 급상승",
    sourceText: "감기 바이러스를 단숨에 사멸시키는 최강의 면역 코팅제",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "면역기능 유지에 도움을 줄 수 있음",
    selector: "p.claim-10",
  },
];

interface ReferenceArcGaugeProps {
  count: number;
  level: "SAFE" | "CAUTION" | "REVIEW";
}

function ReferenceArcGauge({ count, level }: ReferenceArcGaugeProps) {
  const rotationAngleMap = {
    SAFE: 0,
    CAUTION: 90,
    REVIEW: 155,
  };

  const statusColorMap = {
    SAFE: "#10B981",
    CAUTION: "#F59E0B",
    REVIEW: "#EF4444",
  };

  const [knobRotateAngle, setKnobRotateAngle] = useState(0);

  const targetAngle = rotationAngleMap[level];
  const activeColor = statusColorMap[level];

  useEffect(() => {
    setKnobRotateAngle(0);
    const timer = setTimeout(() => {
      setKnobRotateAngle(targetAngle);
    }, 120);
    return () => clearTimeout(timer);
  }, [level, targetAngle]);

  return (
    <div className="reference-gauge-wrap">
      <svg viewBox="0 0 260 145" className="ref-gauge-svg">
        <defs>
          <linearGradient id="pureGreenToRedGrad" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#10B981" />
            <stop offset="35%" stopColor="#FBBF24" />
            <stop offset="70%" stopColor="#F97316" />
            <stop offset="100%" stopColor="#EF4444" />
          </linearGradient>

          <linearGradient id="arcGlassShimmer" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.8" />
            <stop offset="40%" stopColor="#FFFFFF" stopOpacity="0.15" />
            <stop offset="100%" stopColor="#000000" stopOpacity="0.06" />
          </linearGradient>

          <filter id="glassArcShadow" x="-10%" y="-10%" width="120%" height="130%">
            <feDropShadow dx="0" dy="3" stdDeviation="4" floodColor="#0F172A" floodOpacity="0.08" />
          </filter>

          <filter id="knobGlow" x="-30%" y="-30%" width="160%" height="160%">
            <feDropShadow dx="0" dy="2" stdDeviation="3" floodColor="#0F172A" floodOpacity="0.16" />
          </filter>
        </defs>

        <path
          d="M 35 118 A 95 95 0 0 1 225 118"
          fill="none"
          stroke="#F1F5F9"
          strokeWidth="14"
          strokeLinecap="round"
        />

        <path
          d="M 35 118 A 95 95 0 0 1 225 118"
          fill="none"
          stroke="url(#pureGreenToRedGrad)"
          strokeWidth="14"
          strokeLinecap="round"
          filter="url(#glassArcShadow)"
        />

        <path
          d="M 35 118 A 95 95 0 0 1 225 118"
          fill="none"
          stroke="url(#arcGlassShimmer)"
          strokeWidth="14"
          strokeLinecap="round"
          style={{ mixBlendMode: "overlay" }}
        />

        <g
          style={{
            transformOrigin: "130px 118px",
            transform: `rotate(${knobRotateAngle}deg)`,
            transition: "transform 0.95s cubic-bezier(0.16, 1, 0.3, 1)",
          }}
        >
          <circle
            cx="35"
            cy="118"
            r="9.5"
            fill="#FFFFFF"
            stroke={activeColor}
            strokeWidth="3.5"
            filter="url(#knobGlow)"
          />
          <circle cx="35" cy="118" r="3.5" fill="#FFFFFF" />
        </g>
      </svg>

      <div className="ref-gauge-inner-center" style={{ bottom: "34px" }}>
        <span className="ref-count-guide">검토 필요</span>
        <span className="ref-count-num glass-num-shimmer" style={{ color: activeColor }}>
          {count}
        </span>
      </div>

      <div className="ref-bottom-labels">
        <span className="lbl-green">안심</span>
        <span className="lbl-red">검토</span>
      </div>
    </div>
  );
}

export function App() {
  const [status, setStatus] = useState<ViewStatus>("SPLASH");
  const [testTarget, setTestTarget] = useState<TestTarget>("NORMAL");
  const [animCount, setAnimCount] = useState(0);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [viewingHistory, setViewingHistory] = useState<ScanHistoryItem | null>(null);
  const [expandedFindings, setExpandedFindings] = useState<Set<number>>(new Set());
  const [activeFilter, setActiveFilter] = useState<FilterCategory>("ALL");
  const [isCategoryInfoOpen, setIsCategoryInfoOpen] = useState(false);
  const [pendingScrollIdx, setPendingScrollIdx] = useState<number | null>(null);

  // 점검 기록: sessionStorage에 저장 → 브라우저(탭) 세션이 끝나면 자동으로 사라짐
  const [scanHistories, setScanHistories] = useState<ScanHistoryItem[]>(loadScanHistories);

  useEffect(() => {
    try {
      sessionStorage.setItem(SCAN_HISTORY_STORAGE_KEY, JSON.stringify(scanHistories));
    } catch {
      // 시크릿 모드 등 스토리지 접근이 막힌 환경에서는 그냥 메모리 상태로만 동작
    }
  }, [scanHistories]);

  const leftBeamRef = useRef<HTMLSpanElement>(null);
  const rightBeamRef = useRef<HTMLSpanElement>(null);
  const scanTitleRef = useRef<HTMLHeadingElement>(null);

  const targetCount = viewingHistory ? viewingHistory.count : testTarget === "SAFE" ? 0 : MOCK_FINDINGS.length;
  // 히스토리 항목을 보고 있으면 그 항목의 실제 페이지 이름을, 아니면 방금 점검한 현재 페이지 이름을 사용
  const currentPageTitle = viewingHistory ? viewingHistory.productName : CURRENT_PAGE_TITLE;
  // 히스토리 항목을 볼 때는 그 항목의 건수만큼만 목업 문구를 노출해서 배지 숫자와 목록이 어긋나지 않게 함
  const visibleFindings = MOCK_FINDINGS.slice(0, targetCount);

  const diseaseFindings = visibleFindings.filter((f) => f.message.includes("의약품"));
  const guaranteeFindings = visibleFindings.filter((f) => f.message.includes("과장"));

  // idx는 visibleFindings 기준 고유 인덱스로 유지 → 탭으로 필터링해도 펼침 상태가 엉키지 않음
  const filteredFindings = visibleFindings
    .map((finding, idx) => ({ finding, idx }))
    .filter(({ finding }) => {
      if (activeFilter === "DISEASE") return finding.message.includes("의약품");
      if (activeFilter === "GUARANTEE") return finding.message.includes("과장");
      return true;
    });

  function toggleFindingExpanded(idx: number) {
    setExpandedFindings((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) {
        next.delete(idx);
      } else {
        next.add(idx);
      }
      return next;
    });
  }

  function handleBubbleSelect(finding: FindingWithKeyword, idx: number) {
    const category: FilterCategory = finding.message.includes("의약품") ? "DISEASE" : "GUARANTEE";
    setActiveFilter(category);
    setExpandedFindings(new Set([idx]));
    setPendingScrollIdx(idx);
    setStatus("DETAIL_LIST");
  }

  useEffect(() => {
    if (status !== "DETAIL_LIST" || pendingScrollIdx === null) return;
    const timer = setTimeout(() => {
      const target = document.querySelector(`[data-finding-idx="${pendingScrollIdx}"]`);
      target?.scrollIntoView({ behavior: "smooth", block: "center" });
      setPendingScrollIdx(null);
    }, 60);
    return () => clearTimeout(timer);
  }, [status, pendingScrollIdx]);

  useEffect(() => {
    if (status === "SPLASH") {
      const timer = setTimeout(() => {
        setStatus("IDLE");
      }, 1300);
      return () => clearTimeout(timer);
    }
  }, [status]);

  function handleAnalyze() {
    setStatus("ANALYZING");
    setViewingHistory(null);

    setTimeout(() => {
      let currentLevel: "SAFE" | "CAUTION" | "REVIEW" = "REVIEW";
      if (testTarget === "SAFE") {
        setStatus("EMPTY");
        currentLevel = "SAFE";
      } else if (testTarget === "ERROR") {
        setStatus("ERROR");
      } else if (testTarget === "INVALID") {
        setStatus("UNSUPPORTED");
      } else {
        setStatus("SUMMARY_HERO");
        currentLevel = targetCount > 3 ? "REVIEW" : "CAUTION";
      }

      // 분석 완료 시 새로운 기록을 히스토리에 추가
      if (testTarget !== "ERROR" && testTarget !== "INVALID") {
        const newHistoryItem: ScanHistoryItem = {
          id: String(Date.now()),
          dateStr: "방금 전",
          productName: CURRENT_PAGE_TITLE,
          count: targetCount,
          level: currentLevel,
        };
        setScanHistories((prev) => [newHistoryItem, ...prev]);
      }
    }, SCAN_CYCLE_MS);
  }

  const getReviewLevel = (count: number): "SAFE" | "CAUTION" | "REVIEW" => {
    if (count === 0) return "SAFE";
    if (count <= 3) return "CAUTION";
    return "REVIEW";
  };
  const activeLevel = getReviewLevel(targetCount);

  useEffect(() => {
    if (status === "SUMMARY_HERO" || status === "EMPTY") {
      setAnimCount(0);
      const duration = 750;
      const startTime = performance.now();

      function updateAnimation(currentTime: number) {
        const elapsed = currentTime - startTime;
        const progress = Math.min(1, elapsed / duration);
        const easeOut = 1 - Math.pow(1 - progress, 4);

        setAnimCount(Math.round(easeOut * targetCount));

        if (progress < 1) {
          requestAnimationFrame(updateAnimation);
        }
      }

      const timer = setTimeout(() => {
        requestAnimationFrame(updateAnimation);
      }, 100);

      return () => clearTimeout(timer);
    }
  }, [status, targetCount]);

  useEffect(() => {
    if (status !== "ANALYZING") return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    let rafId: number;
    const startTime = performance.now();
    const TIP_LEFT_ANGLE = 38;
    const TIP_RIGHT_ANGLE = -38;
    const TEXT_SCAN_CYCLE_MS = SCAN_CYCLE_MS * 1.18;

    function tick(now: number) {
      const elapsed = now - startTime;
      const cyclePos = elapsed % (SCAN_CYCLE_MS * 2);
      const linearProgress = cyclePos <= SCAN_CYCLE_MS ? cyclePos / SCAN_CYCLE_MS : 2 - cyclePos / SCAN_CYCLE_MS;
      const sweepX = 0.5 - 0.5 * Math.cos(linearProgress * Math.PI);
      const angleDeg = TIP_LEFT_ANGLE + sweepX * (TIP_RIGHT_ANGLE - TIP_LEFT_ANGLE);
      const scaleX = 1 + 0.12 * Math.sin(linearProgress * Math.PI);
      const opacity = 0.9 + 0.1 * Math.sin(linearProgress * Math.PI);

      const beamTransform = `rotate(${angleDeg}deg) scaleX(${scaleX})`;
      for (const beamRef of [leftBeamRef, rightBeamRef]) {
        const beamEl = beamRef.current;
        if (beamEl) {
          beamEl.style.transform = beamTransform;
          beamEl.style.opacity = String(opacity);
        }
      }

      const textCyclePos = elapsed % (TEXT_SCAN_CYCLE_MS * 2);
      const textLinearProgress =
        textCyclePos <= TEXT_SCAN_CYCLE_MS ? textCyclePos / TEXT_SCAN_CYCLE_MS : 2 - textCyclePos / TEXT_SCAN_CYCLE_MS;
      const textSweepX = 0.5 - 0.5 * Math.cos(textLinearProgress * Math.PI);

      const titleEl = scanTitleRef.current;
      if (titleEl) {
        const titleWidth = titleEl.getBoundingClientRect().width;
        const patternBandCenter = titleWidth * 1.5;
        const targetX = textSweepX * titleWidth;
        titleEl.style.backgroundPosition = `${targetX - patternBandCenter}px 0`;
      }

      rafId = requestAnimationFrame(tick);
    }

    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [status]);

  return (
    <div className="toss-root" onClick={() => { if (isCategoryInfoOpen) setIsCategoryInfoOpen(false); }}>
      {status === "SPLASH" && (
        <div className="splash-overlay">
          <div className="splash-content">
            <div className="splash-logo-circle">
              <img
                src="/icons/adcheck_icon.png"
                alt="AdCheck 로고"
                width="72"
                height="72"
                style={{ objectFit: "contain" }}
                onError={(e) => {
                  const target = e.currentTarget;
                  if (target.src.indexOf("./") === -1) {
                    target.src = "./icons/adcheck_icon.png";
                  }
                }}
              />
            </div>
            <div className="glass-shimmer-title splash-title-large">
              <span className="glass-brand-ad">Ad</span>
              <span className="glass-brand-check">Check</span>
            </div>
          </div>
        </div>
      )}

      <header className="toss-header">
        <button
          type="button"
          className="brand-group"
          onClick={() => {
            setViewingHistory(null);
            setStatus("IDLE");
          }}
          aria-label="AdCheck 처음으로"
        >
          <div className="glass-shimmer-title">
            <span className="glass-brand-ad">Ad</span>
            <span className="glass-brand-check">Check</span>
          </div>
        </button>
        {/* 💡 우측 상단 기록 버튼 추가 (스캔 중/결과 요약/안심 아크 화면에서는 숨김) */}
        {status !== "ANALYZING" && status !== "SUMMARY_HERO" && status !== "EMPTY" && (
          <button
            type="button"
            className="btn-history-trigger"
            onClick={() => setIsHistoryOpen(true)}
            aria-label="점검 기록 열기"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="10" />
              <polyline points="12 6 12 12 16 14" />
            </svg>
          </button>
        )}
      </header>

      <main className="toss-viewport">
        <div className="tab-panel">
          {status === "IDLE" && (
            <div className="toss-hero-box stagger-entry">
              <h2 className="hero-title" style={{ marginTop: "4px" }}>
                이 상품 광고,<br />
                <span className="text-dark">믿고 사도 될까요?</span>
              </h2>
              <p className="hero-sub hero-sub-spacious">
                식약처 공식 기능성 인정 기준과<br />
                상세페이지 광고 표현을 실시간 비교해드려요.
              </p>
              <button className="btn-brand-primary btn-idle-margin" type="button" onClick={handleAnalyze}>
                현재 페이지 광고 점검하기
              </button>
            </div>
          )}

          {status === "ANALYZING" && (
            <div className="toss-loading-box">
              <div className="laser-bear-wrap-clean" role="img" aria-label="입체 곰돌이 눈에서 빔을 쏘며 스캔 중">
                <span className="laser-bear-shadow" aria-hidden="true" />
                <div className="laser-bear-face" aria-hidden="true">
                  <img
                    src="/icons/adcheck_icon.png"
                    alt=""
                    className="laser-bear-logo-img"
                    onError={(e) => {
                      const target = e.currentTarget;
                      if (target.src.indexOf("./") === -1) {
                        target.src = "./icons/adcheck_icon.png";
                      }
                    }}
                  />
                  <span className="laser-eye laser-eye-left">
                    <span className="laser-eye-dot" />
                    <span className="laser-beam" ref={leftBeamRef} />
                  </span>
                  <span className="laser-eye laser-eye-right">
                    <span className="laser-eye-dot" />
                    <span className="laser-beam" ref={rightBeamRef} />
                  </span>
                </div>
              </div>
              <h3 className="loading-title loading-title-scan" ref={scanTitleRef}>광고 문구를 꼼꼼히 스캔 중이에요</h3>
              <p className="loading-sub">식약처 공식 고시 기준과 대조하고 있어요</p>
            </div>
          )}

          {status === "SUMMARY_HERO" && (
            <div className="toss-hero-box alert-hero-box">
              <h2 className="hero-title hero-title-giant" style={{ marginBottom: "10px" }}>
                잠깐, 이 표현들을 확인해 보세요
              </h2>
              <p className="hero-sub hero-sub-clean">
                식약처 공식 인정 범위를 넘어선 표현이<br />
                확인되었어요.
              </p>

              <ReferenceArcGauge count={animCount} level={activeLevel} />

              <button
                className="btn-brand-glass-pill btn-summary-margin"
                type="button"
                onClick={() => setStatus("BUBBLE_PREVIEW")}
              >
                <span>어떤 문구인지 확인하기</span>
              </button>
            </div>
          )}

          {status === "BUBBLE_PREVIEW" && (
            <div className="toss-hero-box bubble-preview-box">
              <h2 className="hero-title hero-title-giant" style={{ marginBottom: "10px" }}>
                주요 문구를 먼저 살펴볼까요?
              </h2>
              <p className="hero-sub hero-sub-clean">
                궁금한 문구를 눌러보면<br />
                자세한 내용을 확인할 수 있어요.
              </p>

              <div className="bubble-cloud">
                {visibleFindings.map((finding, idx) => {
                  const isDanger = finding.message.includes("의약품");
                  return (
                    <span
                      key={idx}
                      className="bubble-entry stagger-entry"
                      style={{ animationDelay: `${idx * 0.05}s` }}
                    >
                      <button
                        type="button"
                        className={`preview-bubble ${isDanger ? "preview-bubble-disease" : "preview-bubble-guarantee"}`}
                        style={{ animationDelay: `${(idx % 4) * 0.3}s` }}
                        onClick={() => handleBubbleSelect(finding, idx)}
                      >
                        {finding.bubbleLabel}
                      </button>
                    </span>
                  );
                })}
              </div>

              <button
                className="btn-brand-primary btn-summary-margin"
                type="button"
                onClick={() => {
                  setActiveFilter("ALL");
                  setStatus("DETAIL_LIST");
                }}
              >
                전체 목록 보기
              </button>
            </div>
          )}

          {status === "DETAIL_LIST" && (
            <div className="toss-result-stream">
              <div className="detail-page-nav stagger-entry" style={{ animationDelay: "0.04s" }}>
                <button
                  type="button"
                  className="btn-nav-back"
                  onClick={() => setStatus("SUMMARY_HERO")}
                >
                  ← 요약
                </button>
                <div className="detail-nav-right">
                  <button
                    type="button"
                    className="btn-nav-share"
                    aria-label="검토 결과 공유하기"
                    onClick={() => {
                      const shareText = `[AdCheck 광고 검토 결과]\n총 ${targetCount}건 검토 필요 (오인 우려 표현 ${diseaseFindings.length}건, 과장 표현 ${guaranteeFindings.length}건)`;
                      if (navigator.clipboard?.writeText) {
                        navigator.clipboard
                          .writeText(shareText)
                          .then(() => alert("검토 결과가 클립보드에 복사됐어요."))
                          .catch(() => alert(shareText));
                      } else {
                        alert(shareText);
                      }
                    }}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="18" cy="5" r="3"></circle>
                      <circle cx="6" cy="12" r="3"></circle>
                      <circle cx="18" cy="19" r="3"></circle>
                      <line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line>
                      <line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line>
                    </svg>
                    결과 공유하기
                  </button>
                </div>
              </div>

              <div className="category-tabs-row stagger-entry" style={{ animationDelay: "0.06s" }}>
                <div className="category-tabs">
                  <button
                    type="button"
                    className={`category-tab ${activeFilter === "ALL" ? "tab-active" : ""}`}
                    onClick={() => setActiveFilter("ALL")}
                  >
                    전체
                  </button>
                  <button
                    type="button"
                    className={`category-tab tab-disease ${activeFilter === "DISEASE" ? "tab-active" : ""}`}
                    onClick={() => setActiveFilter("DISEASE")}
                  >
                    오인 우려 표현
                  </button>
                  <button
                    type="button"
                    className={`category-tab tab-guarantee ${activeFilter === "GUARANTEE" ? "tab-active" : ""}`}
                    onClick={() => setActiveFilter("GUARANTEE")}
                  >
                    과장 표현
                  </button>
                </div>

                <div className="category-info-wrap">
                  <button
                    type="button"
                    className="category-info-btn"
                    aria-label="용어 설명 보기"
                    onClick={(e) => {
                      e.stopPropagation();
                      setIsCategoryInfoOpen((prev) => !prev);
                    }}
                  >
                    i
                  </button>
                  {isCategoryInfoOpen && (
                    <div className="category-info-popover" onClick={(e) => e.stopPropagation()}>
                      <div className="info-popover-item">
                        <strong>오인 우려 표현</strong>
                        <p>질병의 예방·치료에 효능이 있는 것처럼 오인될 수 있는 표현이에요.</p>
                      </div>
                      <div className="info-popover-item">
                        <strong>과장 표현</strong>
                        <p>객관적 근거 없이 효능을 절대적으로 보장하거나 부풀린 표현이에요.</p>
                      </div>
                    </div>
                  )}
                </div>
              </div>

              <div className="page-info-card stagger-entry" style={{ animationDelay: "0.07s" }}>
                <div className="page-info-icon-muted">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="2" y1="12" x2="22" y2="12"></line>
                    <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
                  </svg>
                </div>
                <div className="page-info-text-wrap">
                  <span className="page-info-label">분석 대상 페이지</span>
                  <a
                    href="#link"
                    className="page-info-title"
                    onClick={(e) => { e.preventDefault(); alert("현재 활성화된 상세페이지 URL: https://example.com/product/12345"); }}
                  >
                    {currentPageTitle} 외 상세페이지
                  </a>
                </div>
              </div>

              <p className="chips-guide-text chips-guide-standalone stagger-entry" style={{ animationDelay: "0.10s" }}>
                문구를 클릭하면 펼쳐서 공식 기준과 비교해 볼 수 있어요
              </p>

              <div className="finding-feed stagger-entry" style={{ animationDelay: "0.13s" }}>
                {filteredFindings.length === 0 ? (
                  <p className="no-filtered-item">해당 유형의 검토 문구가 없어요.</p>
                ) : (
                  filteredFindings.map(({ finding, idx }) => {
                    const isDanger = finding.message.includes("의약품");
                    const isOpen = expandedFindings.has(idx);
                    return (
                      <div key={idx} className="finding-row" data-finding-idx={idx}>
                        <button
                          type="button"
                          className="finding-row-header"
                          onClick={() => toggleFindingExpanded(idx)}
                          aria-expanded={isOpen}
                        >
                          <span className="finding-row-left">
                            <span className={`cat-chip ${isDanger ? "cat-chip-disease" : "cat-chip-guarantee"}`}>
                              {isDanger ? "오인 우려 표현" : "과장 표현"}
                            </span>
                            <span className="finding-row-title">{finding.keyword}</span>
                          </span>
                          <svg
                            className={`accordion-chevron ${isOpen ? "chevron-open" : ""}`}
                            width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"
                          >
                            <polyline points="6 9 12 15 18 9" />
                          </svg>
                        </button>

                        {isOpen && (
                          <div className="finding-row-content finding-expand-anim">
                            <div className="finding-detail-box">
                              <div className="content-row-clean">
                                <span className="row-label-clean">광고 본문</span>
                                <p className="row-value-bold">"{finding.sourceText}"</p>
                              </div>
                              <div className="content-row-clean border-top-subtle">
                                <span className="row-label-clean">식약처 공식 기준</span>
                                <p className="row-value-regular">{finding.officialFunction}</p>
                              </div>
                            </div>
                            <button
                              type="button"
                              className="finding-row-link"
                              onClick={() => alert(`본문 내 위치: ${finding.selector}`)}
                            >
                              상세페이지 위치 확인하기 ›
                            </button>
                          </div>
                        )}
                      </div>
                    );
                  })
                )}
              </div>

              <div className="detail-bottom-actions stagger-entry" style={{ animationDelay: `${0.18 + visibleFindings.length * 0.02}s` }}>
                <button className="btn-brand-outline" type="button" onClick={handleAnalyze}>
                  다시 점검하기
                </button>
                <button
                  className="btn-brand-primary"
                  type="button"
                  onClick={() => {
                    setViewingHistory(null);
                    setStatus("IDLE");
                  }}
                >
                  새로운 광고 점검하기
                </button>
              </div>
            </div>
          )}

          {status === "EMPTY" && (
            <div className="toss-hero-box safe-box alert-hero-box-safe">
              <h2 className="hero-title hero-title-giant" style={{ marginBottom: "10px" }}>
                발견된 주의 표현이 없어요
              </h2>
              <p className="hero-sub hero-sub-clean">
                식약처 공식 기능성 범위를 벗어나거나<br />
                소비자를 오인시킬 수 있는 문구가 확인되지 않았어요.
              </p>

              <ReferenceArcGauge count={animCount} level={activeLevel} />

              <button 
                className="btn-brand-primary btn-summary-margin" 
                type="button" 
                onClick={handleAnalyze}
              >
                다시 점검하기
              </button>
            </div>
          )}

          {status === "ERROR" && (
            <div className="toss-hero-box">
              <div className="hero-icon-circle icon-circle-red">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#EF4444" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
              </div>
              <h2 className="hero-title">분석 서버에<br />연결할 수 없어요</h2>
              <p className="hero-sub hero-sub-spacious">잠시 후 다시 시도해주세요.</p>
              <button className="btn-brand-primary btn-idle-margin" type="button" onClick={handleAnalyze}>
                다시 시도
              </button>
            </div>
          )}

          {status === "UNSUPPORTED" && (
            <div className="toss-hero-box">
              <div className="hero-icon-circle icon-circle-gray">
                <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="#64748B" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
                </svg>
              </div>
              <h2 className="hero-title">현재 페이지는<br />분석할 수 없어요</h2>
              <p className="hero-sub hero-sub-spacious">상품 상세페이지에서 다시 실행해 주세요.</p>
              
              <button 
                className="btn-brand-primary btn-idle-margin" 
                type="button" 
                onClick={() => setStatus("IDLE")}
              >
                처음으로 돌아가기
              </button>
            </div>
          )}
        </div>
      </main>

      {/* 💡 검색 기록 모달(바텀 시트) 영역 */}
      {isHistoryOpen && (
        <div className="modal-backdrop" onClick={() => setIsHistoryOpen(false)}>
          <div className="modal-bottom-sheet history-sheet" onClick={(e) => e.stopPropagation()}>
            <div className="modal-drag-bar" />
            <div className="modal-head">
              <span className="history-modal-title">최근 점검 기록</span>
              <button
                type="button"
                className="btn-modal-close"
                onClick={() => setIsHistoryOpen(false)}
              >
                ✕
              </button>
            </div>

            <div className="history-list-stack">
              {scanHistories.length === 0 ? (
                <p className="no-filtered-item">저장된 점검 기록이 없어요.</p>
              ) : (
                scanHistories.map((hist) => {
                  const badgeColor = 
                    hist.level === "SAFE" ? "history-badge-safe" : 
                    hist.level === "CAUTION" ? "history-badge-caution" : "history-badge-review";
                  const badgeText = 
                    hist.level === "SAFE" ? "안심" : 
                    hist.level === "CAUTION" ? `주의 ${hist.count}건` : `검토 ${hist.count}건`;

                  return (
                    <div key={hist.id} className="history-item-card" onClick={() => {
                      setIsHistoryOpen(false);
                      setViewingHistory(hist);
                      if (hist.level === "SAFE") {
                        setStatus("EMPTY");
                      } else {
                        // 검토/주의 등급은 요약 화면을 거치지 않고 항상 같은 흐름으로 상세 목록으로 바로 이동
                        setStatus("DETAIL_LIST");
                      }
                    }}>
                      <div className="history-item-info">
                        <span className="history-date">{hist.dateStr}</span>
                        <strong className="history-prod-name">{hist.productName}</strong>
                      </div>
                      <span className={`history-status-badge ${badgeColor}`}>
                        {badgeText}
                      </span>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      )}

      <footer className="toss-footer-area">
        <p className="toss-footer-text">식약처 고시 기준 기반 안내이며, 법적 효력을 갖는 행정처분 결과가 아닙니다.</p>

        <div className="test-switcher">
          <span className="switcher-lbl">테스트:</span>
          <button
            type="button"
            className={testTarget === "NORMAL" ? "sw-btn active" : "sw-btn"}
            onClick={() => { setTestTarget("NORMAL"); setViewingHistory(null); setStatus("IDLE"); }}
          >
            검토(10건)
          </button>
          <button
            type="button"
            className={testTarget === "SAFE" ? "sw-btn active" : "sw-btn"}
            onClick={() => { setTestTarget("SAFE"); setViewingHistory(null); setStatus("IDLE"); }}
          >
            안심(0건)
          </button>
          <button
            type="button"
            className={testTarget === "ERROR" ? "sw-btn active" : "sw-btn"}
            onClick={() => { setTestTarget("ERROR"); setViewingHistory(null); setStatus("IDLE"); }}
          >
            서버오류
          </button>
          <button
            type="button"
            className={testTarget === "INVALID" ? "sw-btn active" : "sw-btn"}
            onClick={() => { setTestTarget("INVALID"); setViewingHistory(null); setStatus("IDLE"); }}
          >
            분석불가
          </button>
        </div>
      </footer>

      <style>{`
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Pretendard", sans-serif; }
        
        .toss-root {
          display: flex;
          flex-direction: column;
          padding: 16px;
          background: #F8FAFC;
          min-height: 100vh;
          color: #1E293B;
          justify-content: space-between;
          position: relative;
        }

        .splash-overlay {
          position: fixed;
          top: 0; left: 0; right: 0; bottom: 0;
          background: #FFFFFF;
          display: flex;
          align-items: center;
          justify-content: center;
          z-index: 9999999;
          animation: splashFadeOut 0.4s ease 0.9s forwards;
        }
        .splash-content {
          display: flex;
          flex-direction: column;
          align-items: center;
          gap: 14px;
          animation: splashPopUp 0.6s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
        }
        .splash-logo-circle {
          width: 88px;
          height: 88px;
          background: #F8FAFC;
          border-radius: 50%;
          display: flex;
          align-items: center;
          justify-content: center;
          box-shadow: 0 12px 32px rgba(15, 23, 42, 0.08);
        }
        .splash-title-large {
          font-size: 32px !important;
        }
        @keyframes splashPopUp {
          0% { opacity: 0; transform: scale(0.85) translateY(10px); }
          100% { opacity: 1; transform: scale(1) translateY(0); }
        }
        @keyframes splashFadeOut {
          0% { opacity: 1; pointer-events: auto; }
          100% { opacity: 0; pointer-events: none; visibility: hidden; }
        }

        .laser-bear-wrap-clean {
          --laser-scan-duration: ${SCAN_CYCLE_MS}ms;
          width: 78px;
          height: 85px;
          display: flex;
          align-items: center;
          justify-content: center;
          margin: 0 auto 32px auto;
          position: relative;
          overflow: visible;
        }

        .laser-bear-shadow {
          position: absolute;
          bottom: 1px;
          width: 42px;
          height: 9px;
          border-radius: 50%;
          background: radial-gradient(ellipse, rgba(6, 78, 71, 0.22), rgba(6, 78, 71, 0) 72%);
          filter: blur(2px);
        }

        .laser-bear-face {
          position: relative;
          width: 68px;
          height: 74px;
        }

        .laser-bear-logo-img {
          width: 100%;
          height: 100%;
          object-fit: contain;
          display: block;
          user-select: none;
          pointer-events: none;
          filter: drop-shadow(0 6px 10px rgba(6, 95, 80, 0.18));
        }

        .laser-eye {
          position: absolute;
          top: 60.2%;
          width: 0;
          height: 0;
          pointer-events: none;
        }

        .laser-eye-left { left: 41.8%; }
        .laser-eye-right { left: 65.1%; }

        .laser-eye-dot {
          position: absolute;
          left: -2.5px;
          top: -2.5px;
          width: 5px;
          height: 5px;
          border-radius: 50%;
          background: #ECFEFF;
          box-shadow: 0 0 4px #5EEAD4, 0 0 6px rgba(20, 184, 166, 0.45);
          animation: laserEyeGlowPulse var(--laser-scan-duration) ease-in-out infinite alternate;
        }

        .laser-beam {
          position: absolute;
          top: 0;
          left: -15px;
          width: 30px;
          height: 88px;
          clip-path: polygon(47% 0%, 53% 0%, 100% 100%, 0% 100%);
          background: linear-gradient(180deg, rgba(236, 254, 255, 0.42) 0%, rgba(94, 234, 212, 0.28) 25%, rgba(20, 184, 166, 0.14) 60%, rgba(20, 184, 166, 0) 100%);
          filter: blur(3.5px) drop-shadow(0 0 4px rgba(20, 184, 166, 0.22));
          transform-origin: top center;
        }

        @keyframes laserEyeGlowPulse {
          0% { opacity: 0.85; transform: scale(0.95); }
          50% { opacity: 1; transform: scale(1.12); }
          100% { opacity: 0.85; transform: scale(0.95); }
        }

        .toss-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin-bottom: 14px;
          padding-bottom: 10px;
          border-bottom: 1px solid #F1F5F9;
        }
        .brand-group { display: flex; align-items: center; background: none; border: none; padding: 0; cursor: pointer; }

        /* 💡 헤더 우측 기록 아이콘 버튼 스타일 */
        .btn-history-trigger {
          width: 36px;
          height: 36px;
          border-radius: 50%;
          background: #FFFFFF;
          border: 1px solid #E2E8F0;
          color: #475569;
          display: flex;
          align-items: center;
          justify-content: center;
          cursor: pointer;
          box-shadow: 0 2px 6px rgba(0,0,0,0.03);
          transition: background 0.15s ease, transform 0.15s ease;
        }
        .btn-history-trigger:hover { background: #F1F5F9; color: #0F172A; }
        .btn-history-trigger:active { transform: scale(0.93); }

        .glass-shimmer-title {
          font-size: 19px;
          font-weight: 900;
          letter-spacing: 0.4px;
          display: flex;
          align-items: center;
          user-select: none;
        }

        .glass-brand-ad { color: #0F172A; margin-right: 1px; }

        .glass-brand-check {
          background: linear-gradient(135deg, #0D9488 0%, #14B8A6 40%, #5EEAD4 70%, #0F766E 100%);
          -webkit-background-clip: text;
          -webkit-text-fill-color: transparent;
          filter: drop-shadow(0 2px 4px rgba(13, 148, 136, 0.2));
          position: relative;
          display: inline-block;
        }

        .toss-viewport { flex: 1; display: flex; flex-direction: column; justify-content: flex-start; }
        .tab-panel { display: flex; flex-direction: column; width: 100%; }

        .toss-hero-box {
          background: #FFFFFF;
          border-radius: 20px;
          padding: 24px 18px 22px 18px;
          text-align: center;
          display: flex;
          flex-direction: column;
          align-items: center;
          box-shadow: 0 4px 16px rgba(0,0,0,0.03);
          border: 1px solid #F1F5F9;
        }

        .reference-gauge-wrap {
          width: 250px;
          position: relative;
          display: flex;
          flex-direction: column;
          align-items: center;
          margin: 4px 0 2px 0;
        }

        .ref-gauge-svg {
          width: 100%;
          height: auto;
          overflow: visible;
        }

        .ref-gauge-inner-center {
          position: absolute;
          bottom: 22px;
          left: 50%;
          transform: translateX(-50%);
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
        }

        .ref-count-guide {
          font-size: 10px;
          font-weight: 700;
          color: #94A3B8;
          letter-spacing: -0.2px;
          margin-bottom: -1px;
        }

        .ref-count-num {
          font-size: 42px;
          font-weight: 800;
          line-height: 1;
          letter-spacing: -1.5px;
          font-variant-numeric: tabular-nums;
        }

        .ref-bottom-labels {
          width: 100%;
          display: flex;
          justify-content: space-between;
          font-size: 11.5px;
          font-weight: 600;
          margin-top: -2px;
          padding: 0 10px;
        }

        .lbl-green { color: #10B981; }
        .lbl-red { color: #EF4444; }

        .alert-hero-box { border: 1px solid rgba(239, 68, 68, 0.12); }
        .alert-hero-box-safe { border: 1px solid rgba(16, 185, 129, 0.2); }

        .hero-title { font-size: 24px; font-weight: 800; line-height: 1.35; color: #0F172A; margin-bottom: 8px; letter-spacing: -0.5px; }
        .hero-title-giant { font-size: 25px; line-height: 1.32; }

        .hero-sub { font-size: 13px; color: #64748B; line-height: 1.5; }
        .hero-sub-clean { margin-bottom: 8px; line-height: 1.55; }
        .hero-sub-spacious { margin-bottom: 0px; line-height: 1.55; }
        
        .text-dark { color: #0F172A; }

        .btn-summary-margin { margin-top: 20px; }
        .btn-idle-margin { margin-top: 22px; }

        .btn-brand-glass-pill {
          width: 100%;
          background: linear-gradient(180deg, #1E293B 0%, #0F172A 100%) !important;
          color: #FFFFFF !important;
          border: 1px solid rgba(255, 255, 255, 0.18);
          padding: 15px 20px;
          border-radius: 28px;
          font-size: 15px;
          font-weight: 600;
          cursor: pointer;
          position: relative;
          display: flex;
          align-items: center;
          justify-content: center;
          box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.35), 0 4px 14px rgba(15, 23, 42, 0.22);
          transition: transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.15s ease;
        }

        .btn-brand-primary {
          width: 100%;
          background: #1E293B !important;
          color: #FFFFFF !important;
          border: 1px solid rgba(255, 255, 255, 0.06);
          padding: 15px;
          border-radius: 28px;
          font-size: 14.5px;
          font-weight: 600;
          letter-spacing: -0.2px;
          cursor: pointer;
          box-shadow: 0 4px 14px rgba(15, 23, 42, 0.22);
          transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.15s ease;
        }
        .btn-brand-primary:hover { background: #334155 !important; }
        .btn-brand-primary:active { transform: scale(0.96); background: #0F172A !important; }

        .detail-bottom-actions { display: flex; flex-direction: column; gap: 8px; }

        .btn-brand-outline {
          width: 100%;
          background: #FFFFFF;
          color: #0F172A;
          border: 1.5px solid #CBD5E1;
          padding: 14.5px;
          border-radius: 28px;
          font-size: 14.5px;
          font-weight: 600;
          letter-spacing: -0.2px;
          cursor: pointer;
          transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.15s ease, border-color 0.15s ease;
        }
        .btn-brand-outline:hover { background: #F8FAFC; border-color: #94A3B8; }
        .btn-brand-outline:active { transform: scale(0.96); }

        .toss-result-stream {
          display: flex;
          flex-direction: column;
          gap: 12px;
        }
        .detail-page-nav { display: flex; justify-content: space-between; align-items: center; padding: 2px; }
        .btn-nav-back { background: transparent; border: none; color: #0F172A; font-size: 12px; font-weight: 800; cursor: pointer; padding: 0; }
        .detail-nav-right { display: flex; align-items: center; gap: 10px; }
        .btn-nav-share {
          display: flex;
          align-items: center;
          gap: 4px;
          background: #F1F5F9;
          border: 1px solid #E2E8F0;
          color: #0D9488;
          font-size: 11px;
          font-weight: 700;
          padding: 5px 9px;
          border-radius: 8px;
          cursor: pointer;
          transition: background 0.15s ease, border-color 0.15s ease;
        }
        .btn-nav-share:hover { background: #ECFEFF; border-color: #99F6E4; }
        .btn-nav-share:active { transform: scale(0.95); }

        .page-info-card {
          background: #FFFFFF;
          border-radius: 14px;
          padding: 12px 14px;
          border: 1px solid #E2E8F0;
          display: flex;
          align-items: center;
          gap: 10px;
          box-shadow: 0 1px 4px rgba(0,0,0,0.02);
        }
        .page-info-icon-muted {
          color: #64748B;
          display: flex;
          align-items: center;
          justify-content: center;
          flex-shrink: 0;
        }
        .page-info-text-wrap { display: flex; flex-direction: column; gap: 1px; overflow: hidden; }
        .page-info-label { font-size: 10px; font-weight: 700; color: #94A3B8; text-transform: uppercase; }
        .page-info-title {
          font-size: 12px;
          font-weight: 700;
          color: #0D9488;
          text-decoration: none;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
        .page-info-title:hover { text-decoration: underline; }

        .chips-guide-text { font-size: 11px; color: #94A3B8; }
        .chips-guide-standalone { padding: 0 2px; margin-top: -2px; }

        .category-tabs-row {
          position: relative;
          z-index: 30;
          display: flex;
          align-items: flex-end;
          gap: 8px;
          border-bottom: 1px solid #F1F5F9;
        }
        .category-tabs {
          display: flex;
          gap: 4px;
          flex: 1;
        }
        .category-tab {
          flex: 1;
          background: transparent;
          border: none;
          border-bottom: 2px solid transparent;
          margin-bottom: -1px;
          padding: 10px 4px;
          font-size: 12.5px;
          font-weight: 700;
          color: #94A3B8;
          cursor: pointer;
          transition: color 0.15s ease, border-color 0.15s ease;
        }
        .category-tab:hover { color: #475569; }
        .category-tab.tab-active { color: #0F172A; border-bottom-color: #0F172A; }
        .category-tab.tab-disease.tab-active { color: #A94438; border-bottom-color: #A94438; }
        .category-tab.tab-guarantee.tab-active { color: #D97706; border-bottom-color: #D97706; }

        .category-info-wrap { position: relative; padding-bottom: 6px; }
        .category-info-btn {
          width: 18px;
          height: 18px;
          border-radius: 50%;
          background: #F1F5F9;
          color: #64748B;
          border: none;
          font-size: 11px;
          font-weight: 800;
          font-style: italic;
          cursor: pointer;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .category-info-btn:hover { background: #E2E8F0; color: #0F172A; }

        .category-info-popover {
          position: absolute;
          top: calc(100% + 8px);
          right: 0;
          width: 220px;
          background: #FFFFFF;
          border: 1px solid #E2E8F0;
          border-radius: 12px;
          padding: 12px 14px;
          box-shadow: 0 10px 24px rgba(15, 23, 42, 0.12);
          z-index: 20;
          display: flex;
          flex-direction: column;
          gap: 10px;
          animation: popoverFadeIn 0.15s ease-out;
        }
        @keyframes popoverFadeIn {
          from { opacity: 0; transform: translateY(-4px); }
          to { opacity: 1; transform: translateY(0); }
        }
        .info-popover-item strong { display: block; font-size: 12px; font-weight: 800; color: #0F172A; margin-bottom: 2px; }
        .info-popover-item p { font-size: 11.5px; color: #64748B; line-height: 1.45; }

        .bubble-preview-box { padding: 26px 20px 24px 20px; }

        .bubble-cloud {
          display: flex;
          flex-wrap: wrap;
          justify-content: center;
          gap: 8px;
          margin: 18px 0 22px 0;
          padding: 4px;
        }
        .bubble-entry { display: inline-flex; }

        @keyframes bubbleFloat {
          0%, 100% { transform: translateY(0); }
          50% { transform: translateY(-4px); }
        }
        .preview-bubble {
          border-radius: 9999px;
          padding: 8px 14px;
          font-size: 12.5px;
          font-weight: 600;
          letter-spacing: -0.1px;
          border: 1px solid transparent;
          cursor: pointer;
          animation: bubbleFloat 3s ease-in-out infinite;
          transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.15s ease, border-color 0.15s ease;
        }
        .preview-bubble:active { transform: scale(0.92); }
        .preview-bubble-disease {
          background: rgba(169, 68, 56, 0.1);
          color: #A94438;
          border-color: rgba(169, 68, 56, 0.22);
        }
        .preview-bubble-disease:hover { background: rgba(169, 68, 56, 0.18); }
        .preview-bubble-guarantee {
          background: rgba(217, 119, 6, 0.1);
          color: #D97706;
          border-color: rgba(217, 119, 6, 0.22);
        }
        .preview-bubble-guarantee:hover { background: rgba(217, 119, 6, 0.18); }
        @media (prefers-reduced-motion: reduce) {
          .preview-bubble { animation: none; }
        }

        .cat-chip {
          font-size: 11px;
          font-weight: 500;
          padding: 3px 7px;
          border-radius: 6px;
          letter-spacing: -0.2px;
          flex-shrink: 0;
        }
        .cat-chip-disease { color: #A94438; background: rgba(169, 68, 56, 0.12); }
        .cat-chip-guarantee { color: #D97706; background: rgba(217, 119, 6, 0.12); }

        .accordion-chevron { color: #94A3B8; transition: transform 0.2s ease; flex-shrink: 0; }
        .accordion-chevron.chevron-open { transform: rotate(180deg); color: #0F172A; }

        .finding-feed {
          background: #FFFFFF;
          border-radius: 16px;
          border: 1px solid #F1F5F9;
          overflow: visible;
        }
        .finding-row { border-bottom: 1px solid #F1F5F9; }
        .finding-row:last-child { border-bottom: none; }
        .finding-row:first-child .finding-row-header { border-radius: 16px 16px 0 0; }
        .finding-row:last-child .finding-row-header { border-radius: 0 0 16px 16px; }
        .finding-row:only-child .finding-row-header { border-radius: 16px; }

        .finding-row-header {
          width: 100%;
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 8px;
          background: transparent;
          border: none;
          padding: 14px 16px;
          cursor: pointer;
          text-align: left;
          transition: background 0.15s ease;
        }
        .finding-row-header:hover { background: #F8FAFC; }

        .finding-row-left { display: flex; align-items: center; gap: 8px; min-width: 0; }
        .finding-row-title {
          font-size: 13px;
          font-weight: 700;
          color: #0F172A;
          letter-spacing: -0.2px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }

        .finding-row-content { padding: 0 16px 16px 16px; }
        @keyframes findingExpand {
          from { opacity: 0; transform: translateY(-6px); }
          to { opacity: 1; transform: translateY(0); }
        }
        .finding-expand-anim { animation: findingExpand 0.22s cubic-bezier(0.34, 1.56, 0.64, 1); }

        .finding-detail-box {
          background: #F8FAFC;
          border-radius: 14px;
          padding: 14px 16px;
          display: flex;
          flex-direction: column;
          gap: 12px;
          border: 1px solid #F1F5F9;
        }
        .finding-row-link {
          display: block;
          width: 100%;
          background: transparent;
          border: none;
          color: #0D9488;
          font-size: 12px;
          font-weight: 700;
          text-align: right;
          padding: 10px 2px 0 2px;
          cursor: pointer;
        }
        .finding-row-link:hover { text-decoration: underline; }

        .no-filtered-item { text-align: center; font-size: 12px; color: #94A3B8; padding: 20px 0; font-weight: 600; }

        .stagger-entry {
          opacity: 0;
          animation: popUpStagger 0.4s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
        }
        @keyframes popUpStagger {
          0% { opacity: 0; transform: translateY(14px) scale(0.96); }
          100% { opacity: 1; transform: translateY(0) scale(1); }
        }

        .modal-backdrop {
          position: fixed; top: 0; left: 0; right: 0; bottom: 0;
          background: rgba(15, 23, 42, 0.5); display: flex; align-items: flex-end; z-index: 100;
          backdrop-filter: blur(2px);
        }
        .modal-bottom-sheet {
          background: #FFFFFF; width: 100%; border-radius: 20px 20px 0 0;
          padding: 16px 20px 24px 20px; display: flex; flex-direction: column; gap: 12px;
          animation: slide-up 0.25s ease-out;
        }
        @keyframes slide-up { from { transform: translateY(100%); } to { transform: translateY(0); } }
        .modal-drag-bar { width: 36px; height: 4px; background: #E2E8F0; border-radius: 2px; align-self: center; margin-bottom: 2px; }
        .modal-head { display: flex; justify-content: space-between; align-items: center; }
        .btn-modal-close { background: transparent; border: none; font-size: 16px; color: #94A3B8; cursor: pointer; }

        .content-row-clean { display: flex; flex-direction: column; gap: 3px; }
        .border-top-subtle { border-top: 1px solid #E2E8F0; padding-top: 10px; }
        .row-label-clean { font-size: 10.5px; font-weight: 700; color: #64748B; }
        .row-value-bold { font-size: 13px; font-weight: 700; color: #0F172A; line-height: 1.45; }
        .row-value-regular { font-size: 12px; color: #334155; line-height: 1.45; }

        /* 💡 기록 모달 전용 스타일 */
        .history-modal-title { font-size: 16px; font-weight: 800; color: #0F172A; }
        .history-list-stack { display: flex; flex-direction: column; gap: 8px; max-height: 320px; overflow-y: auto; margin-top: 4px; }
        .history-item-card {
          background: #F8FAFC; border: 1px solid #E2E8F0; border-radius: 12px;
          padding: 12px; display: flex; align-items: center; justify-content: space-between;
          cursor: pointer; transition: background 0.15s ease;
        }
        .history-item-card:hover { background: #F1F5F9; }
        .history-item-info { display: flex; flex-direction: column; gap: 2px; overflow: hidden; padding-right: 8px; }
        .history-date { font-size: 10.5px; font-weight: 600; color: #94A3B8; }
        .history-prod-name { font-size: 12.5px; font-weight: 700; color: #0F172A; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .history-status-badge { font-size: 11px; font-weight: 700; padding: 4px 8px; border-radius: 8px; flex-shrink: 0; }
        .history-badge-safe { background: #D1FAE5; color: #059669; }
        .history-badge-caution { background: #FEF3C7; color: #D97706; }
        .history-badge-review { background: #FEE2E2; color: #DC2626; }

        .toss-loading-box { text-align: center; padding: 40px 0; }
        .loading-title { font-size: 17px; font-weight: 800; color: #0F172A; margin-bottom: 4px; }
        .loading-title-scan {
          display: inline-block;
          background: linear-gradient(90deg, #0F172A 42%, #2DD4BF 50%, #0F172A 58%);
          background-size: 300% 100%;
          background-repeat: no-repeat;
          -webkit-background-clip: text;
          background-clip: text;
          color: transparent;
        }
        .loading-sub { font-size: 13px; color: #94A3B8; }

        .toss-footer-area { display: flex; flex-direction: column; gap: 8px; margin-top: 14px; }
        .toss-footer-text { text-align: center; font-size: 9.5px; font-weight: 500; color: #94A3B8; line-height: 1.35; padding: 0 4px; }
        
        .test-switcher {
          display: flex; align-items: center; justify-content: center; gap: 4px;
          background: rgba(241, 245, 249, 0.6); padding: 4px 6px; border-radius: 10px;
          border: 1px solid rgba(255, 255, 255, 0.7); box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
        }
        .switcher-lbl { font-size: 9px; font-weight: 700; color: #94A3B8; margin-right: 2px; }
        .sw-btn {
          background: rgba(255, 255, 255, 0.4); border: 1px solid rgba(255, 255, 255, 0.5);
          padding: 4px 7px; border-radius: 6px; font-size: 9px; font-weight: 600; color: #64748B; cursor: pointer;
        }
        .sw-btn.active {
          background: rgba(15, 23, 42, 0.9); border-color: rgba(15, 23, 42, 1); color: #FFFFFF; font-weight: 700;
        }
      `}</style>
    </div>
  );
}