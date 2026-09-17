import React, { useEffect, useState } from 'react';
import type { ReviewLevel } from '../types';
import { MoodFace } from '../components/MoodFace';

// ==========================================
// 결과 안내 화면: 위험 감지 문구 개수 우선 + 3단계 위험도 라벨 + 컬러 곰돌이 얼굴 + 글래시 반원 계기판
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

const RISK_BADGE: Record<RiskTier, { label: string; text: string; glow: string }> = {
  SAFE: { label: '안심', text: '#059669', glow: 'rgba(16, 185, 129, 0.35)' },
  CAUTION: { label: '검토', text: '#D97706', glow: 'rgba(245, 158, 11, 0.35)' },
  REVIEW: { label: '주의', text: '#DC2626', glow: 'rgba(239, 68, 68, 0.35)' },
};

// ==========================================
// 슬림 & 글래시 반원 계기판 사양 정의
// ==========================================
const GAUGE_CX = 130;
const GAUGE_CY = 126;
const GAUGE_OUTER_R = 114;           // 외경
const GAUGE_ACTIVE_OUTER_R = 120;    // 활성 구간만 살짝 볼록하게 확장 (+6px)
const GAUGE_INNER_R = 82;            // 내경 확대 (두께: 42px -> 32px로 약 24% 슬림화하여 여백 확보)
const GAUGE_SWEEP_DEG = 160;
const GAUGE_START_DEG = 270 - GAUGE_SWEEP_DEG / 2;
const GAUGE_GAP_DEG = 1.2;

function gaugePoint(angleDeg: number, radius: number) {
  const rad = (angleDeg * Math.PI) / 180;
  return { x: GAUGE_CX + radius * Math.cos(rad), y: GAUGE_CY + radius * Math.sin(rad) };
}

// 띠(Band) 경로 생성
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

// 바깥 상단 림라이트(빛 반사선) 경로
function outerRimPath(startDeg: number, endDeg: number, outerR: number) {
  const start = gaugePoint(startDeg + 0.5, outerR - 0.8);
  const end = gaugePoint(endDeg - 0.5, outerR - 0.8);
  return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)} A ${outerR - 0.8} ${outerR - 0.8} 0 0 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)}`;
}

// 구간별 글래시 그라데이션 및 색상 매핑
const GAUGE_ZONE_STYLES: { tier: RiskTier; gradId: string; baseColor: string }[] = [
  { tier: 'SAFE', gradId: 'glassGradSafe', baseColor: '#10B981' },
  { tier: 'CAUTION', gradId: 'glassGradCaution', baseColor: '#F59E0B' },
  { tier: 'REVIEW', gradId: 'glassGradReview', baseColor: '#EF4444' },
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

// 바깥 눈금 (스피도미터 틱)
const GAUGE_TICK_COUNT = 24;
const GAUGE_TICKS = Array.from({ length: GAUGE_TICK_COUNT + 1 }, (_, i) => {
  const angle = GAUGE_START_DEG + (i * GAUGE_SWEEP_DEG) / GAUGE_TICK_COUNT;
  const inner = gaugePoint(angle, GAUGE_OUTER_R + 5);
  const outer = gaugePoint(angle, GAUGE_OUTER_R + 10);
  return { angle, inner, outer };
});

function getPointerAngle(tier: RiskTier) {
  const zone = GAUGE_ZONES.find((z) => z.tier === tier)!;
  return zone.midDeg - 90;
}

const GAUGE_POINTER_MIN_ANGLE = GAUGE_START_DEG - 90;
const GAUGE_POINTER_MAX_ANGLE = GAUGE_START_DEG + GAUGE_SWEEP_DEG - 90;
const GAUGE_SPIN_MS = 1300;

export const SummaryHeroView: React.FC<SummaryHeroViewProps> = ({ count, onContinue }) => {
  const tier = getRiskTier(count);
  const badge = RISK_BADGE[tier];
  const isSafe = count === 0;
  const targetPointerAngle = getPointerAngle(tier);

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
          border-radius: 20px;
          padding: 22px 20px 18px;
          display: flex;
          flex-direction: column;
          align-items: center;
          box-sizing: border-box;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          user-select: none;
        }

        .mood-header {
          text-align: center;
          margin-top: 2px;
        }

        .risk-header {
          font-size: 22px;
          font-weight: 800;
          color: #0F172A;
          margin: 0;
          line-height: 1.35;
          letter-spacing: -0.5px;
          word-break: keep-all;
        }

        /* 중앙 곰돌이 영역: 80px -> 66px로 최적화하여 상하 여백 확보 */
        .mood-face-stage {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          margin: 10px 0 4px 0;
          min-height: 72px;
        }

        .bear-pop-enter {
          display: flex;
          flex-direction: column;
          align-items: center;
          animation: bearPopIn 0.45s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
          filter: drop-shadow(0 6px 12px rgba(15, 23, 42, 0.08));
        }

        @keyframes bearPopIn {
          0% { transform: scale(0.55); opacity: 0; }
          60% { transform: scale(1.15); opacity: 1; }
          100% { transform: scale(1); opacity: 1; }
        }

        /* 하단 반원 계기판 */
        .mood-gauge-wrap {
          position: relative;
          width: 100%;
          max-width: 250px;
          margin: 2px auto 0;
          aspect-ratio: 250 / 138;
        }

        .gauge-svg {
          display: block;
          width: 100%;
          height: 100%;
          overflow: visible;
        }

        .gauge-band {
          transition: all 0.35s cubic-bezier(0.4, 0, 0.2, 1);
        }

        .gauge-zone-label {
          font-weight: 700;
          fill: #FFFFFF;
          letter-spacing: -0.3px;
          text-shadow: 0 1px 3px rgba(15, 23, 42, 0.3);
          pointer-events: none;
        }

        /* 포인터 애니메이션 */
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
          height: 50px;
          background: #0F172A;
          color: #FFFFFF;
          border: none;
          border-radius: 14px;
          font-size: 15px;
          font-weight: 600;
          cursor: pointer;
          transition: transform 0.1s ease, background 0.2s ease, box-shadow 0.2s ease;
          display: flex;
          align-items: center;
          justify-content: center;
          box-shadow: 0 6px 16px -4px rgba(15, 23, 42, 0.16);
          margin-top: 12px;
        }

        .mood-cta-btn:active {
          transform: scale(0.98);
          background: #1E293B;
        }
      `}</style>

      {/* 1. 상단 안내 문구 */}
      <div className="mood-header">
        <h2 className="risk-header">
          {isSafe ? (
            <>
              위험 의심문구가
              <br />
              전혀 발견되지 않았어요
            </>
          ) : (
            <>
              위험 감지 문구가
              <br />
              <span style={{ color: badge.text }}>{count}건</span>
              <br />
              발견되었어요
            </>
          )}
        </h2>
      </div>

      {/* 2. 중앙 곰돌이: 66px로 슬림화되어 계기판과 환상적인 비례 구성 */}
      <div className="mood-face-stage">
        {showResult && (
          <div key={tier} className="bear-pop-enter">
            <MoodFace level={tier} size={66} />
          </div>
        )}
      </div>

      {/* 3. 하단 글래시 반원 계기판 */}
      <div className="mood-gauge-wrap">
        <svg className="gauge-svg" viewBox="0 0 260 140">
          <defs>
            {/* 1) 글래시 그라데이션 (상단 빛 반사 효과) */}
            <linearGradient id="glassGradSafe" x1="0%" y1="0%" x2="0%" y2="100%">
              <stop offset="0%" stopColor="#34D399" />
              <stop offset="100%" stopColor="#059669" />
            </linearGradient>
            <linearGradient id="glassGradCaution" x1="0%" y1="0%" x2="0%" y2="100%">
              <stop offset="0%" stopColor="#FBBF24" />
              <stop offset="100%" stopColor="#D97706" />
            </linearGradient>
            <linearGradient id="glassGradReview" x1="0%" y1="0%" x2="0%" y2="100%">
              <stop offset="0%" stopColor="#F87171" />
              <stop offset="100%" stopColor="#DC2626" />
            </linearGradient>

            {/* 2) 활성 구간 네온 글로우 필터 */}
            <filter id="activeZoneGlow" x="-20%" y="-20%" width="140%" height="140%">
              <feDropShadow dx="0" dy="3" stdDeviation="3.5" floodColor={badge.glow} floodOpacity="0.8" />
            </filter>
          </defs>

          {/* 바깥 미니 눈금 */}
          {GAUGE_TICKS.map((tick, i) => (
            <line
              key={i}
              x1={tick.inner.x}
              y1={tick.inner.y}
              x2={tick.outer.x}
              y2={tick.outer.y}
              stroke="#E2E8F0"
              strokeWidth={1.5}
              strokeLinecap="round"
            />
          ))}

          {/* 3색 밴드 (글래시 렌더링) */}
          {GAUGE_ZONES.map((zone) => {
            const isActive = showResult && zone.tier === tier;
            const outerR = isActive ? GAUGE_ACTIVE_OUTER_R : GAUGE_OUTER_R;
            const labelPoint = gaugePoint(zone.midDeg, (GAUGE_INNER_R + outerR) / 2);

            return (
              <g key={zone.tier} filter={isActive ? 'url(#activeZoneGlow)' : undefined}>
                {/* 메인 밴드 바디 */}
                <path
                  className="gauge-band"
                  d={bandPath(zone.startDeg, zone.endDeg, outerR)}
                  fill={`url(#${zone.gradId})`}
                  opacity={isActive ? 1 : 0.38}
                />

                {/* 상단 림라이트 (유리 엣지 반사 하이라이트) */}
                <path
                  d={outerRimPath(zone.startDeg, zone.endDeg, outerR)}
                  fill="none"
                  stroke="#FFFFFF"
                  strokeWidth={1.2}
                  strokeLinecap="round"
                  opacity={isActive ? 0.75 : 0.3}
                />

                {/* 활성 상태일 때 텍스트 라벨 표시 */}
                {isActive && (
                  <text
                    className="gauge-zone-label"
                    x={labelPoint.x}
                    y={labelPoint.y + 0.5}
                    textAnchor="middle"
                    dominantBaseline="middle"
                    fontSize={13.5}
                  >
                    {badge.label}
                  </text>
                )}
              </g>
            );
          })}

          {/* 포인터 */}
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
              d={`M ${GAUGE_CX - 8} ${GAUGE_CY} A 8 8 0 1 1 ${GAUGE_CX + 8} ${GAUGE_CY} Q ${GAUGE_CX + 2.5} ${GAUGE_CY + 18} ${GAUGE_CX} ${GAUGE_CY + 24} Q ${GAUGE_CX - 2.5} ${GAUGE_CY + 18} ${GAUGE_CX - 8} ${GAUGE_CY} Z`}
              fill="#C0C0C0"
            />
            <circle cx={GAUGE_CX} cy={GAUGE_CY} r={2.8} fill="#FFFFFF" />
          </g>
        </svg>
      </div>

      {/* 4. 하단 CTA 버튼 */}
      <button className="mood-cta-btn" onClick={onContinue}>
        {isSafe ? '다시 검사하기' : '어떤 문구인지 확인하기'}
      </button>
    </div>
  );
};

export default SummaryHeroView;
