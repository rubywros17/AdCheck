import React, { useEffect, useState } from 'react';
import type { ReviewLevel } from '../types';
import { MoodFace } from '../components/MoodFace';

// ==========================================
// 결과 안내 화면: 위험 감지 문구 개수 우선 + 3단계 위험도 라벨 + 컬러 곰돌이 얼굴 + 반원 계기판
// ==========================================
interface SummaryHeroViewProps {
  count: number;
  level?: ReviewLevel;
  onContinue?: () => void;
}

type RiskTier = 'SAFE' | 'CAUTION' | 'REVIEW';

// count 기준 3단계 위험도 (안심 / 검토 / 주의)
function getRiskTier(count: number): RiskTier {
  if (count === 0) return 'SAFE';
  if (count <= 4) return 'CAUTION';
  return 'REVIEW';
}

const RISK_BADGE: Record<RiskTier, { label: string; text: string }> = {
  SAFE: { label: '안심', text: '#065F46' },
  CAUTION: { label: '검토', text: '#92400E' },
  REVIEW: { label: '주의', text: '#991B1B' },
};

// ==========================================
// 레퍼런스 스타일 반원 계기판: 3색 밴드 + 바깥 눈금 + 활성 구간 라벨
// (다리(띠) 모양 밴드는 기존 게이지와 동일한 "바깥 호 -> 안쪽 호" 기법을 재사용)
// ==========================================
const GAUGE_CX = 130;
const GAUGE_CY = 128;
const GAUGE_OUTER_R = 118;
const GAUGE_ACTIVE_OUTER_R = GAUGE_OUTER_R + 8; // 활성 구간만 살짝 바깥으로 튀어나오게
const GAUGE_INNER_R = 76;
const GAUGE_SWEEP_DEG = 160;
const GAUGE_START_DEG = 270 - GAUGE_SWEEP_DEG / 2;
const GAUGE_GAP_DEG = 1;

function gaugePoint(angleDeg: number, radius: number) {
  const rad = (angleDeg * Math.PI) / 180;
  return { x: GAUGE_CX + radius * Math.cos(rad), y: GAUGE_CY + radius * Math.sin(rad) };
}

// 바깥 호 -> 안쪽 호를 잇는 두꺼운 띠(밴드) 경로. outerR을 구간마다 다르게 줘서
// 활성 구간만 살짝 부풀어 오르게 할 수 있다.
function bandPath(startDeg: number, endDeg: number, outerR: number) {
  const outerStart = gaugePoint(startDeg, outerR);
  const outerEnd = gaugePoint(endDeg, outerR);
  const innerEnd = gaugePoint(endDeg, GAUGE_INNER_R);
  const innerStart = gaugePoint(startDeg, GAUGE_INNER_R);
  return [
    `M ${outerStart.x.toFixed(2)} ${outerStart.y.toFixed(2)}`,
    `A ${outerR} ${outerR} 0 0 1 ${outerEnd.x.toFixed(2)} ${outerEnd.y.toFixed(2)}`,
    `L ${innerEnd.x.toFixed(2)} ${innerEnd.y.toFixed(2)}`,
    `A ${GAUGE_INNER_R} ${GAUGE_INNER_R} 0 0 0 ${innerStart.x.toFixed(2)} ${innerStart.y.toFixed(2)}`,
    'Z',
  ].join(' ');
}

// 왼쪽부터 안심(초록) → 검토(노랑) → 주의(빨강)
const GAUGE_ZONE_STYLES: { tier: RiskTier; fill: string }[] = [
  { tier: 'SAFE', fill: '#10B981' },
  { tier: 'CAUTION', fill: '#F59E0B' },
  { tier: 'REVIEW', fill: '#EF4444' },
];

const GAUGE_ZONE_RAW_SPAN = GAUGE_SWEEP_DEG / GAUGE_ZONE_STYLES.length;

const GAUGE_ZONES = GAUGE_ZONE_STYLES.map((style, i) => {
  const rawStart = GAUGE_START_DEG + i * GAUGE_ZONE_RAW_SPAN;
  const rawEnd = rawStart + GAUGE_ZONE_RAW_SPAN;
  return {
    ...style,
    startDeg: rawStart + GAUGE_GAP_DEG,
    endDeg: rawEnd - GAUGE_GAP_DEG,
    midDeg: (rawStart + rawEnd) / 2,
  };
});

// 바깥 둘레를 따라 도는 작은 눈금 (스피도미터 느낌)
const GAUGE_TICK_COUNT = 28;
const GAUGE_TICKS = Array.from({ length: GAUGE_TICK_COUNT + 1 }, (_, i) => {
  const angle = GAUGE_START_DEG + (i * GAUGE_SWEEP_DEG) / GAUGE_TICK_COUNT;
  const inner = gaugePoint(angle, GAUGE_OUTER_R + 6);
  const outer = gaugePoint(angle, GAUGE_OUTER_R + 12);
  return { angle, inner, outer };
});

// 포인터: 기본(0deg)이 아래쪽을 가리키는 기준으로, 각 구간 중앙(midDeg)을 향하는 회전각
function getPointerAngle(tier: RiskTier) {
  const zone = GAUGE_ZONES.find((z) => z.tier === tier)!;
  return zone.midDeg - 90;
}

// 포인터가 스핀 중에도 절대 호(arc) 밖으로 나가지 않도록, 왕복 스윙의 양 끝을
// 호의 실제 시작/끝 각도에 맞춰 제한한다 (호 밖으로 나가는 360도 풀회전 금지)
const GAUGE_POINTER_MIN_ANGLE = GAUGE_START_DEG - 90;
const GAUGE_POINTER_MAX_ANGLE = GAUGE_START_DEG + GAUGE_SWEEP_DEG - 90;

const GAUGE_SPIN_MS = 1300;

export const SummaryHeroView: React.FC<SummaryHeroViewProps> = ({ count, onContinue }) => {
  const tier = getRiskTier(count);
  const badge = RISK_BADGE[tier];
  const isSafe = count === 0;
  const targetPointerAngle = getPointerAngle(tier);

  // 계기판이 왼쪽으로 계속 돌아가다가(룰렛처럼) 현재 등급에 감속 정지하면, 그 순간 결과 곰돌이가 팡 튀어오름
  const [isSpinning, setIsSpinning] = useState(true);

  useEffect(() => {
    const prefersReducedMotion =
      typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

    if (prefersReducedMotion) {
      setIsSpinning(false);
      return;
    }

    setIsSpinning(true);
    const timer = window.setTimeout(() => setIsSpinning(false), GAUGE_SPIN_MS);
    return () => window.clearTimeout(timer);
  }, [tier]);

  const showResult = !isSpinning;

  return (
    <div className="mood-summary-container">
      <style>{`
        .mood-summary-container {
          width: 100%;
          background: #FFFFFF;
          padding: 8px 20px 16px;
          display: flex;
          flex-direction: column;
          align-items: center;
          box-sizing: border-box;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          user-select: none;
        }

        .mood-header {
          text-align: center;
          margin-top: 4px;
        }

        .risk-header {
          font-size: 26px;
          font-weight: 800;
          color: #0F172A;
          margin: 0;
          letter-spacing: -0.5px;
        }

        .risk-count-num {
          color: ${badge.text};
          transition: color 0.3s ease;
        }

        /* 중앙 곰돌이 + 그 밑 위험도 문구: 계기판이 멈추는 순간 함께 팡 튀어오르며 등장 */
        .mood-face-stage {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          margin: 14px 0 6px 0;
          min-height: 112px;
        }

        .mood-tier-word {
          margin-top: 6px;
          font-size: 18px;
          font-weight: 800;
          color: ${badge.text};
          letter-spacing: -0.3px;
        }

        .bear-pop-enter {
          display: flex;
          flex-direction: column;
          align-items: center;
          animation: bearPopIn 0.45s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
        }

        @keyframes bearPopIn {
          0% { transform: scale(0.55); opacity: 0; }
          60% { transform: scale(1.18); opacity: 1; }
          100% { transform: scale(1); opacity: 1; }
        }

        /* 하단 반원 계기판 */
        .mood-gauge-wrap {
          position: relative;
          width: 100%;
          max-width: 260px;
          margin: 4px auto 0;
          aspect-ratio: 260 / 145;
        }

        .gauge-svg {
          display: block;
          width: 100%;
          height: 100%;
          overflow: visible;
        }

        .gauge-band {
          transition: fill 0.3s ease, d 0.3s ease;
        }

        /* 포인터: 호 양 끝(${GAUGE_POINTER_MIN_ANGLE}deg~${GAUGE_POINTER_MAX_ANGLE}deg) 안에서만 왕복하다가 감속 정지 — 호 밖으로 나가는 풀회전 금지 */
        .gauge-pointer.spinning {
          animation: gaugeSpinStop ${GAUGE_SPIN_MS}ms cubic-bezier(0.2, 0.7, 0.25, 1) forwards;
        }

        @keyframes gaugeSpinStop {
          0% { transform: rotate(${GAUGE_POINTER_MIN_ANGLE}deg); }
          20% { transform: rotate(${GAUGE_POINTER_MAX_ANGLE}deg); }
          40% { transform: rotate(${GAUGE_POINTER_MIN_ANGLE + 10}deg); }
          60% { transform: rotate(${GAUGE_POINTER_MAX_ANGLE - 10}deg); }
          80% { transform: rotate(calc(var(--target-angle) - 12deg)); }
          100% { transform: rotate(var(--target-angle)); }
        }

        /* 하단 CTA 버튼 */
        .mood-cta-btn {
          width: 100%;
          height: 52px;
          background: #0F172A;
          color: #FFFFFF;
          border: none;
          border-radius: 14px;
          font-size: 15px;
          font-weight: 600;
          cursor: pointer;
          transition: transform 0.1s ease, background 0.2s ease;
          display: flex;
          align-items: center;
          justify-content: center;
          box-shadow: 0 8px 16px -4px rgba(15, 23, 42, 0.15);
          margin-top: 14px;
        }

        .mood-cta-btn:active {
          transform: scale(0.98);
          background: #1E293B;
        }
      `}</style>

      {/* 1. 상단 영역: 위험 감지 문구 개수 헤더 */}
      <div className="mood-header">
        <h2 className="risk-header">
          위험 감지 문구 <span className="risk-count-num">{count}</span>건
        </h2>
      </div>

      {/* 2. 중앙 영역: 계기판이 멈추는 순간에만 결과 곰돌이 + 위험도 문구가 팡 튀어오름 */}
      <div className="mood-face-stage">
        {showResult && (
          <div key={tier} className="bear-pop-enter">
            <MoodFace level={tier} size={80} />
            <p className="mood-tier-word">{badge.label}</p>
          </div>
        )}
      </div>

      {/* 3. 하단 반원 계기판: 3색 밴드 + 바깥 눈금 */}
      <div className="mood-gauge-wrap">
        <svg className="gauge-svg" viewBox="0 0 260 145">
          {/* 바깥 눈금: 스피도미터 느낌의 작은 틱 */}
          {GAUGE_TICKS.map((tick, i) => (
            <line
              key={i}
              x1={tick.inner.x}
              y1={tick.inner.y}
              x2={tick.outer.x}
              y2={tick.outer.y}
              stroke="#CBD5E1"
              strokeWidth={1.5}
              strokeLinecap="round"
            />
          ))}

          {/* 3색 밴드: 계기판이 멈춘 뒤 활성 구간만 살짝 바깥으로 튀어나옴 */}
          {GAUGE_ZONES.map((zone) => {
            const isActive = showResult && zone.tier === tier;
            const outerR = isActive ? GAUGE_ACTIVE_OUTER_R : GAUGE_OUTER_R;
            return (
              <path
                key={zone.tier}
                className="gauge-band"
                d={bandPath(zone.startDeg, zone.endDeg, outerR)}
                fill={zone.fill}
                opacity={isActive ? 1 : 0.55}
              />
            );
          })}

          {/* 양 끝 장식 캡: 레퍼런스의 작은 링 디테일 */}
          <circle cx={gaugePoint(GAUGE_START_DEG, GAUGE_OUTER_R).x} cy={gaugePoint(GAUGE_START_DEG, GAUGE_OUTER_R).y} r={6} fill="#FFFFFF" stroke="#CBD5E1" strokeWidth={2} />
          <circle cx={gaugePoint(GAUGE_START_DEG + GAUGE_SWEEP_DEG, GAUGE_OUTER_R).x} cy={gaugePoint(GAUGE_START_DEG + GAUGE_SWEEP_DEG, GAUGE_OUTER_R).y} r={6} fill="#FFFFFF" stroke="#CBD5E1" strokeWidth={2} />

          {/* 포인터: 왼쪽으로 계속 돌아가다가 현재 등급 쪽에 감속 정지 */}
          <g
            className={isSpinning ? 'gauge-pointer spinning' : 'gauge-pointer'}
            style={{
              transformOrigin: `${GAUGE_CX}px ${GAUGE_CY}px`,
              ...(isSpinning
                ? ({ ['--target-angle' as string]: `${targetPointerAngle}deg` } as React.CSSProperties)
                : { transform: `rotate(${targetPointerAngle}deg)`, transition: 'transform 0.3s ease' }),
            }}
          >
            <path
              d={`M ${GAUGE_CX - 9} ${GAUGE_CY} A 9 9 0 1 1 ${GAUGE_CX + 9} ${GAUGE_CY} Q ${GAUGE_CX + 3} ${GAUGE_CY + 20} ${GAUGE_CX} ${GAUGE_CY + 26} Q ${GAUGE_CX - 3} ${GAUGE_CY + 20} ${GAUGE_CX - 9} ${GAUGE_CY} Z`}
              fill="#334155"
            />
          </g>
        </svg>
      </div>

      {/* 4. 하단 액션 버튼: 0건일 땐 재검사, 그 외엔 상세 확인으로 이동 */}
      <button className="mood-cta-btn" onClick={onContinue}>
        {isSafe ? '다시 검사하기' : '어떤 문구인지 확인하기'}
      </button>
    </div>
  );
};

export default SummaryHeroView;
