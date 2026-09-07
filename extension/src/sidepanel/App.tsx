import React, { useState } from "react";
import type { AnalysisResponse, FindingResponse } from "../types/analysis";

type MainTab = "SCAN" | "HISTORY";
type ViewStatus = "IDLE" | "ANALYZING" | "SUMMARY_HERO" | "DETAIL_LIST" | "EMPTY" | "ERROR" | "UNSUPPORTED";
type TestTarget = "NORMAL" | "SAFE" | "ERROR" | "INVALID";

interface FindingWithKeyword extends FindingResponse {
  keyword: string;
}

interface HistoryItem {
  id: string;
  time: string;
  productName: string;
  url: string;
  status: "CAUTION" | "SAFE";
  count: number;
}

// 🐻 파일 경로 에러가 원천 차단된 100% 에드체크 마스코트 벡터 로고
function AdCheckBear({ size = 48, isStressed = false }: { size?: number; isStressed?: boolean }) {
  // 기본 청록색 vs 60점 이상 붉은색 팔레트
  const baseColor = isStressed ? "#EF4444" : "#14B8A6";
  const earColor = isStressed ? "#DC2626" : "#0D9488";
  const shadeColor = isStressed ? "#B91C1C" : "#0F766E";
  const highlightColor = isStressed ? "#FCA5A5" : "#5EEAD4";
  const eyeColor = isStressed ? "#FEE2E2" : "#A7F3D0";

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={isStressed ? "bear-svg-stressed" : "bear-svg-normal"}
    >
      {/* 왼쪽 귀 */}
      <circle cx="26" cy="26" r="16" fill={earColor} />
      <ellipse cx="23" cy="22" rx="7" ry="5" fill={highlightColor} opacity="0.6" />

      {/* 오른쪽 귀 */}
      <circle cx="74" cy="26" r="16" fill={earColor} />
      <ellipse cx="71" cy="22" rx="7" ry="5" fill={highlightColor} opacity="0.6" />

      {/* 동그란 얼굴 본체 */}
      <circle cx="50" cy="54" r="38" fill={baseColor} />

      {/* 얼굴 하단 볼륨 음영 */}
      <path
        d="M 18 64 C 24 82, 76 82, 82 64 C 74 88, 26 88, 18 64 Z"
        fill={shadeColor}
        opacity="0.35"
      />

      {/* 얼굴 상단 부드러운 광택 */}
      <ellipse cx="45" cy="30" rx="18" ry="10" fill={highlightColor} opacity="0.3" />

      {/* 시그니처 11자 캡슐형 두 눈 */}
      <rect x="36" y="46" width="10" height="22" rx="5" fill={eyeColor} />
      <rect x="54" y="46" width="10" height="22" rx="5" fill={eyeColor} />
    </svg>
  );
}

const MOCK_FINDINGS: FindingWithKeyword[] = [
  {
    keyword: "시력 완벽 회복",
    sourceText: "시력을 완벽히 회복하고 노안과 백내장을 예방합니다.",
    message: "치료·예방 표방",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음",
    selector: "p.claim-1",
  },
  {
    keyword: "노안·백내장 예방",
    sourceText: "시력을 완벽히 회복하고 노안과 백내장을 예방합니다.",
    message: "치료·예방 표방",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음",
    selector: "p.claim-1",
  },
  {
    keyword: "100% 완전 박멸",
    sourceText: "복용 3일 만에 만성 피로 100% 완전 박멸 보장",
    message: "과장·효능 보장",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "피로 개선에 도움을 줄 수 있음",
    selector: "p.claim-2",
  },
  {
    keyword: "기적의 활력 부스터",
    sourceText: "눈의 피로를 즉각 날려주는 기적의 활력 부스터",
    message: "과장·효능 보장",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "눈 건강에 도움을 줄 수 있음",
    selector: "p.claim-3",
  },
];

const INITIAL_HISTORY: HistoryItem[] = [
  {
    id: "h-1",
    time: "방금 전",
    productName: "루테인 지아잔틴 프리미엄 영양제",
    url: "http://localhost:4173/product-page.html",
    status: "CAUTION",
    count: 4,
  },
  {
    id: "h-2",
    time: "14:20",
    productName: "바른 영양 비타민C 1000 순수 정제",
    url: "http://localhost:4173/safe-product.html",
    status: "SAFE",
    count: 0,
  },
];

function calculateRiskScore(findings: FindingWithKeyword[]): number {
  let score = 0;
  for (const f of findings) {
    if (f.message.includes("치료") || f.message.includes("예방")) {
      score += 35;
    } else {
      score += 20;
    }
  }
  return Math.min(score, 100);
}

// 🌟 원형 도넛 게이지 + 빨간 곰돌이 + 빗물 효과
function CircularRiskGauge({ score }: { score: number }) {
  const radius = 64;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (score / 100) * circumference;

  let gaugeColor = "#10B981";
  let statusBadge = "안심할 수 있어요";
  let badgeBg = "#E6F4F2";
  let badgeColor = "#0D9488";

  if (score >= 60) {
    gaugeColor = "#E11D48";
    statusBadge = "점검이 필요해요";
    badgeBg = "#FEE4E2";
    badgeColor = "#D92D20";
  } else if (score >= 40) {
    gaugeColor = "#F59E0B";
    statusBadge = "주의해서 보세요";
    badgeBg = "#FEF0C7";
    badgeColor = "#B54708";
  }

  const isRainActive = score >= 60;

  return (
    <div className="donut-gauge-card">
      <div className="donut-circle-wrap">
        <svg className="donut-svg" width="160" height="160" viewBox="0 0 160 160">
          <circle cx="80" cy="80" r={radius} fill="none" stroke="#F2F4F6" strokeWidth="12" />
          <circle
            cx="80"
            cy="80"
            r={radius}
            fill="none"
            stroke={gaugeColor}
            strokeWidth="12"
            strokeDasharray={circumference}
            strokeDashoffset={strokeDashoffset}
            strokeLinecap="round"
            transform="rotate(-90 80 80)"
            style={{ transition: "stroke-dashoffset 0.8s ease, stroke 0.4s" }}
          />
        </svg>

        <div className="donut-center-content">
          <div className="avatar-rain-wrapper">
            {/* 60점 이상이면 isStressed={true}로 즉시 빨갛게 변함 */}
            <AdCheckBear size={52} isStressed={isRainActive} />

            {/* 60점 이상일 때 곰돌이 옆 빗물/땀방울 효과 */}
            {isRainActive && (
              <div className="sweat-rain-box">
                <span className="rain-drop drop-1" />
                <span className="rain-drop drop-2" />
                <span className="rain-drop drop-3" />
              </div>
            )}
          </div>
          <div className="donut-score-text" style={{ color: gaugeColor }}>
            {score}
            <span className="donut-score-unit">점</span>
          </div>
        </div>
      </div>

      <div className="donut-status-pill" style={{ background: badgeBg, color: badgeColor }}>
        {statusBadge}
      </div>
    </div>
  );
}

export function App() {
  const [currentTab, setCurrentTab] = useState<MainTab>("SCAN");
  const [status, setStatus] = useState<ViewStatus>("IDLE");
  const [testTarget, setTestTarget] = useState<TestTarget>("NORMAL");
  const [historyList, setHistoryList] = useState<HistoryItem[]>(INITIAL_HISTORY);
  const [selectedFinding, setSelectedFinding] = useState<FindingWithKeyword | null>(null);

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
    }, 700);
  }

  const riskScore = calculateRiskScore(MOCK_FINDINGS);
  const officialStandard = MOCK_FINDINGS[0]?.officialFunction ?? "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음";

  return (
    <div className="toss-root">
      {/* 1. 상단 미니멀 헤더 */}
      <header className="toss-header">
        <div className="brand-group">
          <AdCheckBear size={22} />
          <span className="brand-title">AdCheck</span>
        </div>

        <nav className="tab-pill-box">
          <button
            type="button"
            className={`tab-btn ${currentTab === "SCAN" ? "active" : ""}`}
            onClick={() => setCurrentTab("SCAN")}
          >
            점검
          </button>
          <button
            type="button"
            className={`tab-btn ${currentTab === "HISTORY" ? "active" : ""}`}
            onClick={() => setCurrentTab("HISTORY")}
          >
            기록 <span className="history-count">{historyList.length}</span>
          </button>
        </nav>
      </header>

      {/* 2. 메인 뷰포트 */}
      <main className="toss-viewport">
        {currentTab === "SCAN" ? (
          <div className="tab-panel">
            {/* [대기 화면] */}
            {status === "IDLE" && (
              <div className="toss-hero-box">
                <div className="bear-hero-circle">
                  <AdCheckBear size={54} />
                </div>
                <h2 className="hero-title">
                  이 상품 광고,<br />
                  <span className="text-mint">믿고 사도 될까요?</span>
                </h2>
                <p className="hero-sub">
                  식약처 공식 기능성 인정 기준과<br />
                  상세페이지 광고 표현을 3초 만에 비교해드려요.
                </p>
                <button className="btn-toss-primary" type="button" onClick={handleAnalyze}>
                  광고 점검하기
                </button>
              </div>
            )}

            {/* [분석 중 로딩] */}
            {status === "ANALYZING" && (
              <div className="toss-loading-box">
                <div className="toss-spinner" />
                <h3 className="loading-title">광고 문구를 확인하고 있어요</h3>
                <p className="loading-sub">식약처 공공 DB와 대조 중이에요</p>
              </div>
            )}

            {/* [1단계 요약 히어로 창: 빨간 곰돌이] */}
            {status === "SUMMARY_HERO" && (
              <div className="toss-hero-box alert-hero-box">
                <div className="bear-hero-circle circle-alert-pulse">
                  <AdCheckBear size={54} isStressed={true} />
                </div>
                <span className="alert-pill-chip">잠깐만요!</span>
                <h2 className="hero-title hero-title-giant">
                  주의해서 볼 표현이<br />
                  <span className="highlight-giant-num">{MOCK_FINDINGS.length}개</span> 있어요
                </h2>
                <p className="hero-sub">
                  식약처가 인정한 기능성 범위를 넘어선<br />
                  과장·치료 표방 표현이 감지되었어요.
                </p>

                <button
                  className="btn-toss-primary"
                  type="button"
                  onClick={() => setStatus("DETAIL_LIST")}
                >
                  어떤 문구인지 확인하기 →
                </button>
              </div>
            )}

            {/* [2단계 상세 리스트 화면: 원형 도넛 게이지 + 단어 칩] */}
            {status === "DETAIL_LIST" && (
              <div className="toss-result-stream">
                <div className="detail-page-nav">
                  <button
                    type="button"
                    className="btn-nav-back"
                    onClick={() => setStatus("SUMMARY_HERO")}
                  >
                    ← 요약으로 돌아가기
                  </button>
                  <span className="detail-nav-badge">총 {MOCK_FINDINGS.length}건 분석됨</span>
                </div>

                {/* 🌟 원형 도넛 게이지 & 빨간 곰돌이 */}
                <CircularRiskGauge score={riskScore} />

                {/* 🌟 단어 칩 섹션 */}
                <div className="chips-section-card">
                  <div className="chips-header">
                    <strong className="chips-title">감지된 의심 표현들</strong>
                    <span className="chips-guide-text">단어를 눌러 상세 내용을 확인하세요</span>
                  </div>

                  <div className="chips-cloud">
                    {MOCK_FINDINGS.map((finding, idx) => {
                      const isTreatment = finding.message.includes("치료") || finding.message.includes("예방");
                      return (
                        <button
                          key={idx}
                          type="button"
                          className={`word-chip ${isTreatment ? "chip-red" : "chip-amber"}`}
                          onClick={() => setSelectedFinding(finding)}
                        >
                          <span className="chip-hash">#</span>
                          <span className="chip-text">{finding.keyword}</span>
                          <span className="chip-arrow">›</span>
                        </button>
                      );
                    })}
                  </div>
                </div>

                <button className="btn-toss-sub" type="button" onClick={handleAnalyze}>
                  다시 점검하기
                </button>

                {/* 하단 식약처 기준 안내 도크 */}
                <div className="official-standard-dock-bottom">
                  <div className="dock-head">
                    <span className="dock-pin">📌</span>
                    <span className="dock-title">식약처 공식 인정 기준 안내</span>
                  </div>
                  <p className="dock-body">“{officialStandard}”</p>
                  <span className="dock-foot">위 공식 고시 범위를 벗어난 질병 예방·치료 및 완치 보장 표현은 주의하세요.</span>
                </div>
              </div>
            )}

            {/* [안심 완료] */}
            {status === "EMPTY" && (
              <div className="toss-hero-box safe-box">
                <div className="bear-hero-circle">
                  <AdCheckBear size={54} />
                </div>
                <span className="safe-pill">안심 확인 완료</span>
                <h2 className="hero-title">
                  현재 확인이 필요한<br />
                  <span className="text-mint">표현을 찾지 못했습니다</span>
                </h2>
                <p className="hero-sub">
                  식약처 인정 범위를 벗어나거나<br />
                  소비자를 오인시키는 과장 표현이 감지되지 않았어요.
                </p>
                <button className="btn-toss-sub" type="button" onClick={handleAnalyze}>
                  다시 점검하기
                </button>
              </div>
            )}

            {/* [서버 오류] */}
            {status === "ERROR" && (
              <div className="toss-hero-box">
                <div className="bear-hero-circle circle-alert-pulse">
                  <AdCheckBear size={54} isStressed={true} />
                </div>
                <h2 className="hero-title">분석 서버에<br />연결할 수 없습니다</h2>
                <p className="hero-sub">잠시 후 다시 시도해주세요.</p>
                <button className="btn-toss-primary" type="button" onClick={handleAnalyze}>
                  다시 시도
                </button>
              </div>
            )}

            {/* [분석 불가] */}
            {status === "UNSUPPORTED" && (
              <div className="toss-hero-box">
                <div className="bear-hero-circle">
                  <AdCheckBear size={54} />
                </div>
                <h2 className="hero-title">현재 페이지는<br />분석할 수 없습니다</h2>
                <p className="hero-sub">상품 상세페이지에서 다시 실행해 주세요.</p>
                <button className="btn-toss-sub" type="button" onClick={() => setStatus("IDLE")}>
                  처음으로 돌아가기
                </button>
              </div>
            )}
          </div>
        ) : (
          /* [기록 화면] */
          <div className="tab-panel history-panel">
            <div className="history-header-row">
              <span className="today-label">TODAY</span>
              <button
                type="button"
                className="btn-clear-history"
                onClick={() => setHistoryList([])}
              >
                전체 삭제
              </button>
            </div>

            {historyList.length === 0 ? (
              <div className="empty-history-box">
                <span className="empty-icon">📂</span>
                <p>오늘 검사한 기록이 없습니다.</p>
              </div>
            ) : (
              <div className="history-stream">
                {historyList.map((item) => (
                  <div
                    key={item.id}
                    className="history-card"
                    onClick={() => {
                      if (item.status === "CAUTION") {
                        setStatus("SUMMARY_HERO");
                      } else {
                        setStatus("EMPTY");
                      }
                      setCurrentTab("SCAN");
                    }}
                  >
                    <div className="history-top">
                      <span className="history-time">{item.time}</span>
                      {item.status === "CAUTION" ? (
                        <span className="history-badge badge-warn">주의 {item.count}건</span>
                      ) : (
                        <span className="history-badge badge-safe">안심 통과</span>
                      )}
                    </div>
                    <strong className="history-product-title">{item.productName}</strong>
                    <span className="history-url">{item.url}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </main>

      {/* 바텀시트 모달 */}
      {selectedFinding && (
        <div className="modal-backdrop" onClick={() => setSelectedFinding(null)}>
          <div className="modal-bottom-sheet" onClick={(e) => e.stopPropagation()}>
            <div className="modal-drag-bar" />
            <div className="modal-head">
              <span className={`modal-tag ${selectedFinding.message.includes("치료") ? "tag-red" : "tag-amber"}`}>
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

            <h4 className="modal-keyword-title">“{selectedFinding.keyword}”</h4>

            <div className="modal-quote-box">
              <span className="quote-label">광고 본문 문장</span>
              <p className="quote-full-text">“{selectedFinding.sourceText}”</p>
            </div>

            <div className="modal-official-box">
              <span className="official-dock-tag">식약처 공식 기준</span>
              <p className="official-dock-text">{selectedFinding.officialFunction}</p>
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

      {/* 푸터 & 테스트 스위처 */}
      <footer className="toss-footer-area">
        <p className="toss-footer-text">식품안전나라 공공데이터 기반 · 법적 효력 없음</p>

        {currentTab === "SCAN" && (
          <div className="test-switcher">
            <span className="switcher-lbl">테스트:</span>
            <button
              type="button"
              className={testTarget === "NORMAL" ? "sw-btn active" : "sw-btn"}
              onClick={() => { setTestTarget("NORMAL"); setStatus("IDLE"); }}
            >
              결과(위험)
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
        )}
      </footer>

      {/* 스타일시트 */}
      <style>{`
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Pretendard", sans-serif; }
        
        .toss-root {
          display: flex;
          flex-direction: column;
          padding: 16px;
          background: #F4F6F8;
          min-height: 100vh;
          color: #191F28;
          justify-content: space-between;
        }

        .toss-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          margin-bottom: 8px;
        }
        .brand-group { display: flex; align-items: center; gap: 8px; }
        .brand-title { font-size: 17px; font-weight: 800; color: #191F28; letter-spacing: -0.4px; }

        .tab-pill-box {
          display: flex;
          background: #E5E8EB;
          padding: 3px;
          border-radius: 10px;
          gap: 2px;
        }
        .tab-btn {
          border: none;
          background: transparent;
          padding: 5px 12px;
          border-radius: 7px;
          font-size: 12px;
          font-weight: 700;
          color: #64748B;
          cursor: pointer;
          display: flex;
          align-items: center;
          gap: 4px;
        }
        .tab-btn.active {
          background: #FFFFFF;
          color: #0D9488;
          box-shadow: 0 1px 3px rgba(0,0,0,0.06);
        }
        .history-count {
          background: #CCFBF1;
          color: #0D9488;
          font-size: 10px;
          padding: 1px 5px;
          border-radius: 10px;
        }

        .toss-viewport { flex: 1; display: flex; flex-direction: column; justify-content: center; }
        .tab-panel { display: flex; flex-direction: column; width: 100%; }

        .toss-hero-box {
          background: #FFFFFF;
          border-radius: 24px;
          padding: 36px 20px;
          text-align: center;
          display: flex;
          flex-direction: column;
          align-items: center;
          box-shadow: 0 4px 16px rgba(0,0,0,0.03);
        }

        /* 곰돌이 서클 */
        .bear-hero-circle {
          width: 76px;
          height: 76px;
          border-radius: 50%;
          background: #E6F4F2;
          display: flex;
          align-items: center;
          justify-content: center;
          margin-bottom: 16px;
        }

        .circle-alert-pulse {
          background: #FEE4E2;
          box-shadow: 0 0 0 6px rgba(254, 228, 226, 0.6);
        }

        .bear-svg-stressed {
          animation: bear-alarm-shake 0.6s ease-in-out;
        }
        @keyframes bear-alarm-shake {
          0%, 100% { transform: scale(1) rotate(0deg); }
          25% { transform: scale(1.08) rotate(-6deg); }
          75% { transform: scale(1.08) rotate(6deg); }
        }

        .alert-hero-box { border: 1px solid rgba(225, 29, 72, 0.08); }
        .alert-pill-chip {
          background: #FEE4E2;
          color: #D92D20;
          font-size: 12px;
          font-weight: 800;
          padding: 4px 12px;
          border-radius: 20px;
          margin-bottom: 12px;
        }
        .hero-title { font-size: 21px; font-weight: 800; line-height: 1.35; color: #191F28; margin-bottom: 10px; letter-spacing: -0.5px; }
        .hero-title-giant { font-size: 25px; line-height: 1.32; }
        .highlight-giant-num { color: #E11D48; font-size: 34px; font-weight: 900; }
        .hero-sub { font-size: 13px; color: #8B95A1; line-height: 1.5; margin-bottom: 24px; }
        .text-mint { color: #0D9488; }

        /* 원형 도넛 게이지 */
        .donut-gauge-card {
          background: #FFFFFF;
          border-radius: 24px;
          padding: 22px 16px;
          display: flex;
          flex-direction: column;
          align-items: center;
          box-shadow: 0 4px 16px rgba(0,0,0,0.03);
          margin-bottom: 10px;
        }
        .donut-circle-wrap {
          position: relative;
          width: 160px;
          height: 160px;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .donut-svg { position: absolute; top: 0; left: 0; }
        .donut-center-content {
          display: flex;
          flex-direction: column;
          align-items: center;
          z-index: 2;
        }
        .avatar-rain-wrapper {
          position: relative;
          width: 54px;
          height: 54px;
          display: flex;
          align-items: center;
          justify-content: center;
        }

        /* 🌧️ 빗물 효과 */
        .sweat-rain-box {
          position: absolute;
          top: -2px;
          right: -8px;
          display: flex;
          gap: 3px;
        }
        .rain-drop {
          width: 2px;
          background: #4B5563;
          border-radius: 2px;
          animation: rain-fall 0.8s infinite ease-in-out;
        }
        .drop-1 { height: 14px; animation-delay: 0s; }
        .drop-2 { height: 18px; animation-delay: 0.15s; }
        .drop-3 { height: 12px; animation-delay: 0.3s; }
        @keyframes rain-fall {
          0%, 100% { transform: translateY(0); opacity: 0.8; }
          50% { transform: translateY(4px); opacity: 0.3; }
        }

        .donut-score-text { font-size: 30px; font-weight: 900; margin-top: 4px; line-height: 1; }
        .donut-score-unit { font-size: 16px; font-weight: 700; margin-left: 2px; }
        .donut-status-pill {
          margin-top: 14px; font-size: 13px; font-weight: 800; padding: 6px 18px; border-radius: 20px;
        }

        /* 단어 칩 */
        .chips-section-card {
          background: #FFFFFF;
          border-radius: 20px;
          padding: 16px;
          display: flex;
          flex-direction: column;
          gap: 12px;
          box-shadow: 0 4px 16px rgba(0,0,0,0.03);
        }
        .chips-header { display: flex; flex-direction: column; gap: 2px; }
        .chips-title { font-size: 14px; font-weight: 800; color: #191F28; }
        .chips-guide-text { font-size: 11px; color: #8B95A1; }
        .chips-cloud { display: flex; flex-wrap: wrap; gap: 8px; }
        .word-chip {
          border: none; padding: 8px 12px; border-radius: 12px; font-size: 13px; font-weight: 700;
          cursor: pointer; display: flex; align-items: center; gap: 4px; transition: transform 0.1s;
        }
        .word-chip:active { transform: scale(0.96); }
        .chip-red { background: #FEE4E2; color: #D92D20; }
        .chip-amber { background: #FEF0C7; color: #B54708; }
        .chip-hash { opacity: 0.6; font-weight: 800; }
        .chip-arrow { font-size: 14px; margin-left: 2px; opacity: 0.7; }

        /* 바텀시트 모달 */
        .modal-backdrop {
          position: fixed; top: 0; left: 0; right: 0; bottom: 0;
          background: rgba(0, 0, 0, 0.45); display: flex; align-items: flex-end; z-index: 100;
          backdrop-filter: blur(2px);
        }
        .modal-bottom-sheet {
          background: #FFFFFF; width: 100%; border-radius: 24px 24px 0 0;
          padding: 16px 20px 24px 20px; display: flex; flex-direction: column; gap: 12px;
          animation: slide-up 0.25s ease-out;
        }
        @keyframes slide-up { from { transform: translateY(100%); } to { transform: translateY(0); } }
        .modal-drag-bar { width: 36px; height: 4px; background: #E5E8EB; border-radius: 2px; align-self: center; margin-bottom: 4px; }
        .modal-head { display: flex; justify-content: space-between; align-items: center; }
        .modal-tag { font-size: 11px; font-weight: 800; padding: 3px 8px; border-radius: 6px; }
        .tag-red { background: #FEE4E2; color: #D92D20; }
        .tag-amber { background: #FEF0C7; color: #B54708; }
        .btn-modal-close { background: transparent; border: none; font-size: 16px; color: #8B95A1; cursor: pointer; }
        .modal-keyword-title { font-size: 18px; font-weight: 800; color: #191F28; }
        .modal-quote-box {
          background: #F9FAFB; border-left: 3px solid #E11D48; padding: 10px 12px;
          border-radius: 0 10px 10px 0; display: flex; flex-direction: column; gap: 4px;
        }
        .quote-label { font-size: 10px; font-weight: 700; color: #8B95A1; }
        .quote-full-text { font-size: 13px; font-weight: 700; color: #191F28; line-height: 1.4; }
        .modal-official-box {
          background: #F0FDFA; border-radius: 12px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px;
        }
        .official-dock-tag { font-size: 10px; font-weight: 800; color: #0D9488; }
        .official-dock-text { font-size: 12px; color: #0F766E; line-height: 1.4; }
        .btn-modal-action {
          width: 100%; background: #0D9488; color: #FFFFFF; border: none; padding: 14px;
          border-radius: 14px; font-size: 14px; font-weight: 700; cursor: pointer; margin-top: 4px;
        }

        .toss-result-stream { display: flex; flex-direction: column; gap: 10px; }
        .detail-page-nav { display: flex; justify-content: space-between; align-items: center; padding: 2px 2px 4px 2px; }
        .btn-nav-back { background: transparent; border: none; color: #0D9488; font-size: 12px; font-weight: 700; cursor: pointer; padding: 0; }
        .detail-nav-badge { font-size: 11px; font-weight: 700; color: #8B95A1; }

        .official-standard-dock-bottom {
          background: #FFFFFF; border: 1px solid #E5E8EB; border-radius: 14px; padding: 12px 14px;
          margin-top: 4px; display: flex; flex-direction: column; gap: 4px;
        }
        .dock-head { display: flex; align-items: center; gap: 5px; }
        .dock-pin { font-size: 12px; }
        .dock-title { font-size: 11px; font-weight: 800; color: #0D9488; }
        .dock-body { font-size: 12px; font-weight: 700; color: #333D4B; line-height: 1.4; }
        .dock-foot { font-size: 10px; color: #8B95A1; line-height: 1.35; margin-top: 2px; }

        .btn-toss-primary { width: 100%; background: #0D9488; color: #FFFFFF; border: none; padding: 15px; border-radius: 16px; font-size: 15px; font-weight: 700; cursor: pointer; }
        .btn-toss-sub { width: 100%; background: #E6F4F2; color: #0D9488; border: none; padding: 13px; border-radius: 16px; font-size: 13px; font-weight: 700; cursor: pointer; margin-top: 2px; }
        .toss-loading-box { text-align: center; padding: 40px 0; }
        .toss-spinner { width: 36px; height: 36px; border: 3px solid #E5E8EB; border-top-color: #0D9488; border-radius: 50%; margin: 0 auto 16px; animation: spin 0.8s ease-in-out infinite; }
        @keyframes spin { to { transform: rotate(360deg); } }
        .loading-title { font-size: 17px; font-weight: 800; color: #191F28; margin-bottom: 4px; }
        .loading-sub { font-size: 13px; color: #8B95A1; }
        .safe-pill { background: #E6F4F2; color: #0D9488; font-size: 11px; font-weight: 700; padding: 4px 10px; border-radius: 20px; margin-bottom: 10px; }

        .history-panel { display: flex; flex-direction: column; gap: 8px; }
        .history-header-row { display: flex; justify-content: space-between; align-items: center; padding: 0 4px; }
        .today-label { font-size: 12px; font-weight: 800; color: #64748B; }
        .btn-clear-history { background: transparent; border: none; font-size: 11px; font-weight: 600; color: #94A3B8; cursor: pointer; }
        .history-stream { display: flex; flex-direction: column; gap: 8px; max-height: 480px; overflow-y: auto; }
        .history-card {
          background: #FFFFFF; border-radius: 14px; padding: 12px 14px; display: flex; flex-direction: column; gap: 4px;
          box-shadow: 0 2px 8px rgba(0,0,0,0.02); cursor: pointer; border-left: 4px solid #0D9488;
        }
        .history-top { display: flex; justify-content: space-between; align-items: center; }
        .history-time { font-size: 11px; font-weight: 600; color: #8B95A1; }
        .history-badge { font-size: 10px; font-weight: 700; padding: 2px 6px; border-radius: 4px; }
        .badge-warn { background: #FEE4E2; color: #F04438; }
        .badge-safe { background: #E6F4F2; color: #0D9488; }
        .history-product-title { font-size: 13px; font-weight: 700; color: #191F28; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .history-url { font-size: 10px; color: #94A3B8; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .empty-history-box { background: #FFFFFF; border-radius: 16px; padding: 30px 16px; text-align: center; color: #8B95A1; font-size: 13px; }

        .toss-footer-area { display: flex; flex-direction: column; gap: 6px; margin-top: 12px; }
        .toss-footer-text { text-align: center; font-size: 10px; font-weight: 500; color: #B0B8C1; }
        .test-switcher { display: flex; align-items: center; justify-content: center; gap: 4px; background: #E5E8EB; padding: 4px; border-radius: 8px; }
        .switcher-lbl { font-size: 9px; font-weight: 700; color: #64748B; margin-right: 2px; }
        .sw-btn { background: #FFFFFF; border: none; padding: 3px 6px; border-radius: 4px; font-size: 9px; font-weight: 600; color: #64748B; cursor: pointer; }
        .sw-btn.active { background: #0D9488; color: #FFFFFF; font-weight: 700; }
      `}</style>
    </div>
  );
}