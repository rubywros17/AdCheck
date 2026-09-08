import React, { useState, useEffect } from "react";
import type { FindingResponse } from "../types/analysis";

type ViewStatus = "SPLASH" | "IDLE" | "ANALYZING" | "SUMMARY_HERO" | "DETAIL_LIST" | "EMPTY" | "ERROR" | "UNSUPPORTED";
type TestTarget = "NORMAL" | "SAFE" | "ERROR" | "INVALID";
type FilterCategory = "ALL" | "DISEASE" | "GUARANTEE";

const SCAN_CYCLE_MS = 2400;

interface FindingWithKeyword extends FindingResponse {
  keyword: string;
}

const MOCK_FINDINGS: FindingWithKeyword[] = [
  {
    keyword: "노안·백내장 근본 예방 및 시력 100% 완벽 회복 보장 특급 솔루션",
    sourceText: "본 영양제는 단 2주일 만에 노안과 백내장을 근본적으로 예방하고 시력을 100% 완벽히 회복시켜 드립니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음",
    selector: "p.claim-1",
  },
  {
    keyword: "손상된 간세포 즉각 재생",
    sourceText: "잦은 음주로 극심하게 파괴된 간세포를 혁신적으로 즉각 재생시켜 줍니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "간 건강에 도움을 줄 수 있음",
    selector: "p.claim-2",
  },
  {
    keyword: "만성 관절염 완치",
    sourceText: "시큰거리는 퇴행성 관절염 통증을 며칠 만에 깨끗하게 완치 보장합니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "관절 및 연골건강에 도움을 줄 수 있음",
    selector: "p.claim-3",
  },
  {
    keyword: "혈관 핏떡 100% 융해",
    sourceText: "혈액 속 뭉친 혈전과 핏떡을 100% 녹여내어 뇌졸중을 막아줍니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "혈중 중성지질 개선·혈행개선에 도움을 줄 수 있음",
    selector: "p.claim-4",
  },
  {
    keyword: "체지방 100% 완전 분해",
    sourceText: "운동이나 식단 조절 전혀 없이도 섭취된 탄수화물과 체지방을 100% 태웁니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "탄수화물이 지방으로 합성되는 것을 억제하여 체지방 감소에 도움을 줄 수 있음",
    selector: "p.claim-5",
  },
  {
    keyword: "기적의 활력 부스터",
    sourceText: "먹자마자 3초 만에 만성 피로가 즉각 날아가는 기적의 에너지 폭탄",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "피로개선에 도움을 줄 수 있음",
    selector: "p.claim-6",
  },
  {
    keyword: "단 3일 7kg 감량 보장",
    sourceText: "임상 증명 완료! 3일간 섭취하면 무조건 체중 7kg 감량을 보장해 드립니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "체지방 감소에 도움을 줄 수 있음",
    selector: "p.claim-7",
  },
  {
    keyword: "일일 권장량 1000% 배합",
    sourceText: "시중 제품과는 차원이 다른 슈퍼 고단위 압축 배합으로 효과가 10배 뛰어납니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "영양소 보충 및 건강 증진에 도움을 줄 수 있음",
    selector: "p.claim-8",
  },
  {
    keyword: "전문의 만장일치 보증",
    sourceText: "대한민국 최고 권위 전문의들이 직접 효과를 보증하고 만장일치로 추천한 제품",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "건강기능식품 공통 기준",
    selector: "p.claim-9",
  },
  {
    keyword: "초고속 면역력 급상승",
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
            transition: "transform 0.95s cubic-bezier(0.34, 1.56, 0.64, 1)",
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
  const [selectedFinding, setSelectedFinding] = useState<FindingWithKeyword | null>(null);
  const [animCount, setAnimCount] = useState(0);
  const [activeFilter, setActiveFilter] = useState<FilterCategory>("ALL");
  const [tooltipPos, setTooltipPos] = useState<{ top: number; left: number } | null>(null);

  const diseaseFindings = MOCK_FINDINGS.filter((f) => f.message.includes("의약품"));
  const guaranteeFindings = MOCK_FINDINGS.filter((f) => f.message.includes("과장"));
  const targetCount = testTarget === "SAFE" ? 0 : MOCK_FINDINGS.length;

  const filteredFindings = MOCK_FINDINGS.filter((f) => {
    if (activeFilter === "DISEASE") return f.message.includes("의약품");
    if (activeFilter === "GUARANTEE") return f.message.includes("과장");
    return true;
  });

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

    setTimeout(() => {
      if (testTarget === "SAFE") {
        setStatus("EMPTY");
      } else if (testTarget === "ERROR") {
        setStatus("ERROR");
      } else if (testTarget === "INVALID") {
        setStatus("UNSUPPORTED");
      } else {
        setStatus("SUMMARY_HERO");
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

  return (
    <div className="toss-root" onClick={() => { if (tooltipPos) setTooltipPos(null); }}>
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
        <div className="brand-group">
          <div className="glass-shimmer-title">
            <span className="glass-brand-ad">Ad</span>
            <span className="glass-brand-check">Check</span>
          </div>
        </div>
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
              {/* 💡 입체감이 살아있는 공식 로고 곰돌이 눈 위치에서 빔을 쏘며 좌우 스캔 */}
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
                    <span className="laser-beam" />
                  </span>
                  <span className="laser-eye laser-eye-right">
                    <span className="laser-eye-dot" />
                    <span className="laser-beam" />
                  </span>
                </div>
              </div>
              <h3 className="loading-title">광고 문구를 꼼꼼히 스캔 중이에요</h3>
              <p className="loading-sub">식약처 공식 고시 기준과 대조하고 있어요</p>
            </div>
          )}

          {status === "SUMMARY_HERO" && (
            <div className="toss-hero-box alert-hero-box">
              <h2 className="hero-title hero-title-giant" style={{ marginBottom: "4px" }}>
                광고 검토 결과 안내
              </h2>
              <p className="hero-sub hero-sub-clean">
                식약처 공식 인정 범위를 넘어선 표현이<br />
                다수 확인되었습니다.
              </p>

              <ReferenceArcGauge count={animCount} level={activeLevel} />

              <button
                className="btn-brand-glass-pill btn-summary-margin"
                type="button"
                onClick={() => {
                  setActiveFilter("ALL");
                  setStatus("DETAIL_LIST");
                }}
              >
                <span>어떤 문구인지 확인하기</span>
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
                <span className="detail-nav-badge">총 {targetCount}건 안내</span>
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
                    프리미엄 눈 건강 루테인 지아잔틴 1000mg 외 상세페이지
                  </a>
                </div>
              </div>

              <div className="issue-summary-card stagger-entry" style={{ animationDelay: "0.10s" }}>
                <div className="issue-summary-top">
                  <div className="summary-title-with-info">
                    <span className="summary-title-main">유형별 검토 현황</span>
                    <button 
                      type="button" 
                      className="info-icon-btn"
                      onClick={(e) => {
                        e.stopPropagation();
                        const rect = e.currentTarget.getBoundingClientRect();
                        if (tooltipPos) {
                          setTooltipPos(null);
                        } else {
                          setTooltipPos({
                            top: rect.bottom + 6,
                            left: rect.left - 10,
                          });
                        }
                      }}
                    >
                      ℹ
                    </button>
                  </div>
                  <span className="summary-sub-desc">탭을 눌러 필터링</span>
                </div>

                <div className="issue-pills-row">
                  <button
                    type="button"
                    className={`issue-pill ${activeFilter === "ALL" ? "filter-active" : ""}`}
                    onClick={() => setActiveFilter("ALL")}
                  >
                    <span className="pill-title">전체</span>
                    <span className="pill-num">{targetCount}</span>
                  </button>

                  <button
                    type="button"
                    className={`issue-pill ${activeFilter === "DISEASE" ? "filter-active" : ""}`}
                    onClick={() => setActiveFilter("DISEASE")}
                  >
                    <span className="dot-box box-red">●</span>
                    <span className="pill-title">의약품 오인</span>
                    <span className="pill-num">{diseaseFindings.length}</span>
                  </button>

                  <button
                    type="button"
                    className={`issue-pill ${activeFilter === "GUARANTEE" ? "filter-active" : ""}`}
                    onClick={() => setActiveFilter("GUARANTEE")}
                  >
                    <span className="dot-box box-yellow">●</span>
                    <span className="pill-title">과장 광고</span>
                    <span className="pill-num">{guaranteeFindings.length}</span>
                  </button>
                </div>
              </div>

              <div className="chips-section-card stagger-entry" style={{ animationDelay: "0.16s" }}>
                <div className="chips-header">
                  <strong className="chips-title">
                    {activeFilter === "ALL" && "상세 확인 문구 목록 (전체)"}
                    {activeFilter === "DISEASE" && "상세 확인 문구 목록 (의약품 오인)"}
                    {activeFilter === "GUARANTEE" && "상세 확인 문구 목록 (과장 광고)"}
                  </strong>
                  <span className="chips-guide-text">문구를 터치하면 공식 기준과 비교해 볼 수 있어요</span>
                </div>

                <div className="chips-list-stack">
                  {filteredFindings.length === 0 ? (
                    <p className="no-filtered-item">해당 유형의 검토 문구가 없습니다.</p>
                  ) : (
                    filteredFindings.map((finding, idx) => {
                      const isDanger = finding.message.includes("의약품");
                      return (
                        <button
                          key={idx}
                          type="button"
                          className="word-chip-item stagger-entry"
                          style={{ animationDelay: `${0.20 + idx * 0.04}s` }}
                          onClick={() => setSelectedFinding(finding)}
                        >
                          <div className="chip-left-meta">
                            <span className={`chip-dot-indicator ${isDanger ? "dot-indicator-red" : "dot-indicator-yellow"}`}>
                              ●
                            </span>
                            <span className="chip-keyword-title">{finding.keyword}</span>
                          </div>
                          <span className="chip-arrow-icon">›</span>
                        </button>
                      );
                    })
                  )}
                </div>
              </div>

              <div className="stagger-entry" style={{ animationDelay: `${0.22 + filteredFindings.length * 0.04}s` }}>
                <button className="btn-brand-primary" type="button" onClick={handleAnalyze}>
                  다시 점검하기
                </button>
              </div>
            </div>
          )}

          {status === "EMPTY" && (
            <div className="toss-hero-box safe-box alert-hero-box-safe">
              <h2 className="hero-title hero-title-giant" style={{ marginBottom: "4px" }}>
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
              <h2 className="hero-title">분석 서버에<br />연결할 수 없습니다</h2>
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
              <h2 className="hero-title">현재 페이지는<br />분석할 수 없습니다</h2>
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

      {tooltipPos && (
        <div 
          className="global-fixed-tooltip" 
          style={{ top: tooltipPos.top, left: tooltipPos.left }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="tooltip-item">
            <span className="dot-red">●</span> <strong>의약품 오인:</strong> 질병의 예방 및 치료에 효능이 있는 것으로 오인될 우려가 있는 표현
          </div>
          <div className="tooltip-item">
            <span className="dot-yellow">●</span> <strong>과장 광고:</strong> 객관적 근거 없이 효능을 절대적으로 보장하거나 소비자를 기만하는 표현
          </div>
        </div>
      )}

      {selectedFinding && (
        <div className="modal-backdrop" onClick={() => setSelectedFinding(null)}>
          <div className="modal-bottom-sheet" onClick={(e) => e.stopPropagation()}>
            <div className="modal-drag-bar" />
            <div className="modal-head">
              <span className={`modal-tag ${selectedFinding.message.includes("의약품") ? "tag-red" : "tag-amber"}`}>
                ● {selectedFinding.message}
              </span>
              <button
                type="button"
                className="btn-modal-close"
                onClick={() => setSelectedFinding(null)}
              >
                ✕
              </button>
            </div>

            <h4 className="modal-keyword-title">{selectedFinding.keyword}</h4>

            <div className="modal-content-group">
              <div className="content-row-clean">
                <span className="row-label-clean">광고 본문</span>
                <p className="row-value-bold">“{selectedFinding.sourceText}”</p>
              </div>

              <div className="content-row-clean border-top-subtle">
                <span className="row-label-clean">식약처 공식 기준</span>
                <p className="row-value-regular">{selectedFinding.officialFunction}</p>
              </div>
            </div>

            <button
              type="button"
              className="btn-modal-action"
              onClick={() => {
                alert(`본문 내 위치: ${selectedFinding.selector}`);
                setSelectedFinding(null);
              }}
            >
              상세페이지 위치 확인하기
            </button>
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
            onClick={() => { setTestTarget("NORMAL"); setStatus("IDLE"); }}
          >
            검토(10건)
          </button>
          <button
            type="button"
            className={testTarget === "SAFE" ? "sw-btn active" : "sw-btn"}
            onClick={() => { setTestTarget("SAFE"); setStatus("IDLE"); }}
          >
            안심(0건)
          </button>
          <button
            type="button"
            className={testTarget === "ERROR" ? "sw-btn active" : "sw-btn"}
            onClick={() => { setTestTarget("ERROR"); setStatus("IDLE"); }}
          >
            서버오류
          </button>
          <button
            type="button"
            className={testTarget === "INVALID" ? "sw-btn active" : "sw-btn"}
            onClick={() => { setTestTarget("INVALID"); setStatus("IDLE"); }}
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

        /* 💡 입체 곰돌이 로고에 맞춘 투명 배경 레이저 스캔 스타일 */
        .laser-bear-wrap-clean {
          --laser-scan-duration: ${SCAN_CYCLE_MS}ms;
          width: 92px;
          height: 100px;
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
          width: 50px;
          height: 10px;
          border-radius: 50%;
          background: radial-gradient(ellipse, rgba(6, 78, 71, 0.22), rgba(6, 78, 71, 0) 72%);
          filter: blur(2px);
        }

        .laser-bear-face {
          position: relative;
          width: 80px;
          height: 87px;
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
          left: -13px;
          width: 26px;
          height: 74px;
          clip-path: polygon(47% 0%, 53% 0%, 100% 100%, 0% 100%);
          background: linear-gradient(180deg, rgba(236, 254, 255, 0.5) 0%, rgba(94, 234, 212, 0.34) 25%, rgba(20, 184, 166, 0.18) 60%, rgba(20, 184, 166, 0) 100%);
          filter: blur(1.5px) drop-shadow(0 0 3px rgba(20, 184, 166, 0.3));
          transform-origin: top center;
          animation: laserBeamSweep var(--laser-scan-duration) ease-in-out infinite alternate;
        }

        @keyframes laserBeamSweep {
          0% {
            transform: rotate(-20deg) scaleX(1);
            opacity: 0.9;
          }
          50% {
            transform: rotate(4deg) scaleX(1.12);
            opacity: 1;
          }
          100% {
            transform: rotate(27deg) scaleX(1);
            opacity: 0.9;
          }
        }

        @keyframes laserEyeGlowPulse {
          0% { opacity: 0.85; transform: scale(0.95); }
          50% { opacity: 1; transform: scale(1.12); }
          100% { opacity: 0.85; transform: scale(0.95); }
        }

        @media (prefers-reduced-motion: reduce) {
          .laser-beam, .laser-eye-dot {
            animation: none;
          }
        }

        .toss-header {
          display: flex;
          align-items: center;
          justify-content: flex-start;
          margin-bottom: 14px;
          padding-bottom: 10px;
          border-bottom: 1px solid #F1F5F9;
        }
        .brand-group { display: flex; align-items: center; }

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

        .hero-title { font-size: 20px; font-weight: 800; line-height: 1.35; color: #0F172A; margin-bottom: 8px; letter-spacing: -0.5px; }
        .hero-title-giant { font-size: 21px; line-height: 1.32; }

        .hero-sub { font-size: 13px; color: #64748B; line-height: 1.5; }
        .hero-sub-clean { margin-bottom: 8px; line-height: 1.55; }
        .hero-sub-spacious { margin-bottom: 0px; line-height: 1.55; }
        
        .text-dark { color: #0F172A; }
        .text-green { color: #059669; }

        .btn-summary-margin {
          margin-top: 20px;
        }

        .btn-idle-margin {
          margin-top: 22px;
        }

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
        .btn-brand-glass-pill:hover {
          box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.5), 0 6px 18px rgba(15, 23, 42, 0.28);
        }
        .btn-brand-glass-pill:active {
          transform: scale(0.96);
          box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.4), 0 2px 6px rgba(15, 23, 42, 0.15);
        }

        .btn-brand-primary {
          width: 100%;
          background: #0F172A !important;
          color: #FFFFFF !important;
          border: none;
          padding: 15px;
          border-radius: 28px;
          font-size: 14.5px;
          font-weight: 600;
          letter-spacing: -0.2px;
          cursor: pointer;
          transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), background 0.2s ease;
        }
        .btn-brand-primary:active { transform: scale(0.96); background: #020617 !important; }

        .btn-modal-action {
          width: 100%; background: #0F172A; color: #FFFFFF; border: none; padding: 14px;
          border-radius: 14px; font-size: 14.5px; font-weight: 600; letter-spacing: -0.2px; cursor: pointer; margin-top: 6px;
        }

        .toss-result-stream {
          display: flex;
          flex-direction: column;
          gap: 12px;
          max-height: calc(100vh - 120px);
          overflow-y: auto;
          padding-right: 2px;
        }
        .detail-page-nav { display: flex; justify-content: space-between; align-items: center; padding: 2px; }
        .btn-nav-back { background: transparent; border: none; color: #0F172A; font-size: 12px; font-weight: 800; cursor: pointer; padding: 0; }
        .detail-nav-badge { font-size: 11px; font-weight: 700; color: #94A3B8; }

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

        .issue-summary-card {
          background: #FFFFFF;
          border-radius: 16px;
          padding: 14px 14px;
          border: 1px solid #F1F5F9;
          box-shadow: 0 2px 8px rgba(0,0,0,0.03);
          display: flex;
          flex-direction: column;
          gap: 10px;
        }
        .issue-summary-top { display: flex; justify-content: space-between; align-items: center; }
        
        .summary-title-with-info { display: flex; align-items: center; gap: 6px; position: relative; }
        .summary-title-main { font-size: 13px; font-weight: 800; color: #0F172A; }
        
        .info-icon-btn {
          width: 16px; height: 16px; border-radius: 50%;
          background: #E2E8F0; color: #475569; border: none;
          font-size: 10px; font-weight: 900; cursor: pointer;
          display: flex; align-items: center; justify-content: center;
          transition: background 0.15s ease;
        }
        .info-icon-btn:hover { background: #CBD5E1; color: #0F172A; }

        .global-fixed-tooltip {
          position: fixed;
          width: 240px;
          background: #FFFFFF;
          color: #1E293B;
          padding: 12px 14px;
          border-radius: 12px;
          border: 1px solid #CBD5E1;
          font-size: 11px;
          line-height: 1.45;
          box-shadow: 0 10px 30px rgba(15, 23, 42, 0.2);
          z-index: 999999;
          animation: tooltipFadeIn 0.18s cubic-bezier(0.34, 1.56, 0.64, 1);
        }
        @keyframes tooltipFadeIn {
          from { opacity: 0; transform: translateY(-4px) scale(0.96); }
          to { opacity: 1; transform: translateY(0) scale(1); }
        }
        .tooltip-item { margin-bottom: 6px; color: #475569; }
        .tooltip-item:last-child { margin-bottom: 0; }
        .dot-red { color: #EF4444; }
        .dot-yellow { color: #F59E0B; }

        .summary-sub-desc { font-size: 11px; color: #94A3B8; font-weight: 600; }

        .issue-pills-row { display: flex; gap: 6px; width: 100%; }
        
        .issue-pill {
          flex: 1;
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 4px;
          padding: 8px 4px;
          border-radius: 10px;
          font-size: 11px;
          font-weight: 600;
          background: #F8FAFC;
          border: 1.5px solid #E2E8F0;
          cursor: pointer;
          transition: all 0.15s ease;
          white-space: nowrap;
        }
        .issue-pill:hover { background: #F1F5F9; border-color: #CBD5E1; }

        .pill-title { color: #475569; font-size: 11px; }
        .pill-num { font-weight: 800; font-size: 11px; color: #64748B; }

        .dot-box { font-size: 8px; line-height: 1; }
        .box-red { color: #EF4444; }
        .box-yellow { color: #F59E0B; }

        .filter-active {
          background: #0F172A !important;
          border-color: #0F172A !important;
          box-shadow: 0 2px 6px rgba(15, 23, 42, 0.2);
        }
        .filter-active .pill-title { color: #FFFFFF !important; font-weight: 800; }
        .filter-active .pill-num { color: #FFFFFF !important; }

        .chips-section-card {
          background: #FFFFFF;
          border-radius: 18px;
          padding: 16px;
          display: flex;
          flex-direction: column;
          gap: 12px;
          box-shadow: 0 2px 10px rgba(0,0,0,0.03);
          border: 1px solid #F1F5F9;
        }
        .chips-header { display: flex; flex-direction: column; gap: 2px; }
        .chips-title { font-size: 14px; font-weight: 800; color: #0F172A; }
        .chips-guide-text { font-size: 11px; color: #94A3B8; }

        .chips-list-stack { display: flex; flex-direction: column; gap: 8px; }
        .word-chip-item {
          width: 100%;
          background: #F8FAFC;
          border: 1px solid #E2E8F0;
          border-radius: 12px;
          padding: 10px 12px;
          display: flex;
          align-items: center;
          justify-content: space-between;
          cursor: pointer;
          transition: background 0.15s ease, border-color 0.15s ease, transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1);
          text-align: left;
        }
        .word-chip-item:hover { background: #F1F5F9; border-color: #CBD5E1; }
        .word-chip-item:active { transform: scale(0.95); }

        .chip-left-meta { display: flex; align-items: center; gap: 10px; flex: 1; overflow: hidden; }
        .chip-dot-indicator {
          font-size: 11px;
          line-height: 1;
          display: flex;
          align-items: center;
          justify-content: center;
          flex-shrink: 0;
        }
        .dot-indicator-red { color: #EF4444; filter: drop-shadow(0 0 3px rgba(239, 68, 68, 0.4)); }
        .dot-indicator-yellow { color: #F59E0B; filter: drop-shadow(0 0 3px rgba(245, 158, 11, 0.4)); }

        .chip-keyword-title {
          font-size: 13px;
          font-weight: 700;
          color: #0F172A;
          letter-spacing: -0.2px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
        .chip-arrow-icon { font-size: 16px; color: #94A3B8; flex-shrink: 0; margin-left: 8px; }

        .no-filtered-item { text-align: center; font-size: 12px; color: #94A3B8; padding: 20px 0; font-weight: 600; }

        @keyframes popUpStagger {
          0% { opacity: 0; transform: translateY(14px) scale(0.96); }
          70% { opacity: 1; transform: translateY(-2px) scale(1.015); }
          100% { opacity: 1; transform: translateY(0) scale(1); }
        }

        .stagger-entry {
          opacity: 0;
          animation: popUpStagger 0.4s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
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
        .modal-tag { font-size: 11px; font-weight: 800; padding: 3px 8px; border-radius: 6px; }
        .tag-red { background: #FEE2E2; color: #DC2626; }
        .tag-amber { background: #FEF3C7; color: #D97706; }
        .btn-modal-close { background: transparent; border: none; font-size: 16px; color: #94A3B8; cursor: pointer; }
        .modal-keyword-title { font-size: 15px; font-weight: 800; color: #0F172A; line-height: 1.35; margin-bottom: 2px; }

        .modal-content-group {
          background: #F8FAFC;
          border-radius: 14px;
          padding: 14px 16px;
          display: flex;
          flex-direction: column;
          gap: 12px;
          border: 1px solid #F1F5F9;
        }
        .content-row-clean {
          display: flex;
          flex-direction: column;
          gap: 3px;
        }
        .border-top-subtle {
          border-top: 1px solid #E2E8F0;
          padding-top: 10px;
        }
        .row-label-clean {
          font-size: 10.5px;
          font-weight: 700;
          color: #64748B;
        }
        .row-value-bold {
          font-size: 13px;
          font-weight: 700;
          color: #0F172A;
          line-height: 1.45;
        }
        .row-value-regular {
          font-size: 12px;
          color: #334155;
          line-height: 1.45;
        }

        .toss-loading-box { text-align: center; padding: 40px 0; }
        .loading-title { font-size: 17px; font-weight: 800; color: #0F172A; margin-bottom: 4px; }
        .loading-sub { font-size: 13px; color: #94A3B8; }

        .toss-footer-area { display: flex; flex-direction: column; gap: 8px; margin-top: 14px; }
        .toss-footer-text { text-align: center; font-size: 9.5px; font-weight: 500; color: #94A3B8; line-height: 1.35; padding: 0 4px; }
        
        .test-switcher {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 4px;
          background: rgba(241, 245, 249, 0.6);
          backdrop-filter: blur(10px);
          -webkit-backdrop-filter: blur(10px);
          padding: 4px 6px;
          border-radius: 10px;
          border: 1px solid rgba(255, 255, 255, 0.7);
          box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
        }
        .switcher-lbl { font-size: 9px; font-weight: 700; color: #94A3B8; margin-right: 2px; }
        .sw-btn {
          background: rgba(255, 255, 255, 0.4);
          border: 1px solid rgba(255, 255, 255, 0.5);
          padding: 4px 7px;
          border-radius: 6px;
          font-size: 9px;
          font-weight: 600;
          color: #64748B;
          cursor: pointer;
          transition: all 0.15s ease;
        }
        .sw-btn:hover { background: rgba(255, 255, 255, 0.8); color: #0F172A; }
        .sw-btn.active {
          background: rgba(15, 23, 42, 0.9);
          backdrop-filter: blur(4px);
          border-color: rgba(15, 23, 42, 1);
          color: #FFFFFF;
          font-weight: 700;
          box-shadow: 0 2px 4px rgba(15, 23, 42, 0.15);
        }
      `}</style>
    </div>
  );
}