import { useEffect, useState } from "react";
import type { ReviewLevel } from "../types";

interface Props {
  count: number;
  level: ReviewLevel;
}

// 블록 3개: SAFE=연두, CAUTION=노랑, REVIEW=주황 (파스텔 톤 — '판단/경고'보다 '탐색/자기성찰' 느낌)
const STATUS_COLORS: Record<ReviewLevel, string> = { SAFE: "#A7F3D0", CAUTION: "#FDE68A", REVIEW: "#FDBA74" };
// 비활성 블록은 옅은 회색으로 깔아 활성 블록만 도드라지게 함
const INACTIVE_COLOR = "#E2E8F0";

const LEVEL_ORDER: ReviewLevel[] = ["SAFE", "CAUTION", "REVIEW"];

// 반원(180도)을 60도씩 3등분한 중앙 각도에 블록을 하나씩 배치 (30 / 90 / 150)
// 기준(회전 0도) 위치는 호의 왼쪽 끝(35,118)이며, 이 각도만큼 호의 중심(CX,CY)을 축으로 시계방향 회전시켜
// 각 블록을 호 위의 제자리로 옮긴다. 노브도 동일한 각도 체계를 공유해 항상 활성 블록을 가리킨다.
const BLOCK_ANGLES: Record<ReviewLevel, number> = { SAFE: 30, CAUTION: 90, REVIEW: 150 };
const ROTATION_ANGLES = BLOCK_ANGLES;

// 호 중심 / 반지름 (기존 레이아웃 그대로 유지)
const CX = 130;
const CY = 118;
const R = 95;
const ARC_LEFT_X = CX - R; // 35
const ARC_LEFT_Y = CY; // 118

// 블록 크기: 호를 따라가는 방향(길이)과 반지름 방향(두께). 기준 위치(호의 왼쪽 끝)에서는
// 접선이 수직이므로 세로로 길게 그려두고, 회전을 거치면 호의 접선 방향에 맞춰 자연스럽게 눕는다.
const BLOCK_LENGTH = 42;
const BLOCK_THICKNESS = 16;
const BLOCK_CORNER_RADIUS = 6;

export function ReferenceArcGauge({ count, level }: Props) {
  const [knobRotateAngle, setKnobRotateAngle] = useState(0);
  const [animatedCount, setAnimatedCount] = useState(0);
  const targetAngle = ROTATION_ANGLES[level];
  const activeColor = STATUS_COLORS[level];

  useEffect(() => {
    setKnobRotateAngle(0);
    const timer = window.setTimeout(() => setKnobRotateAngle(targetAngle), 120);
    return () => window.clearTimeout(timer);
  }, [targetAngle]);

  useEffect(() => {
    setAnimatedCount(0);
    let frameId = 0;
    const duration = 750;
    const startTime = performance.now();
    const updateAnimation = (currentTime: number) => {
      const progress = Math.min(1, (currentTime - startTime) / duration);
      const easeOut = 1 - Math.pow(1 - progress, 4);
      setAnimatedCount(Math.round(easeOut * count));
      if (progress < 1) frameId = requestAnimationFrame(updateAnimation);
    };
    const timer = window.setTimeout(() => {
      frameId = requestAnimationFrame(updateAnimation);
    }, 100);
    return () => {
      window.clearTimeout(timer);
      cancelAnimationFrame(frameId);
    };
  }, [count]);

  return (
    <div className="reference-gauge-wrap" role="img" aria-label={`검토 필요 ${count}건`}>
      <svg viewBox="0 0 260 145" className="ref-gauge-svg" aria-hidden="true">
        <defs>
          {/* 활성 블록 위 아주 은은한 확산광 (탐색 톤: 짙은 그림자 대신 부드러운 컬러 글로우) */}
          <filter id="activeBlockGlow" x="-80%" y="-80%" width="260%" height="260%">
            <feDropShadow dx="0" dy="2" stdDeviation="2.5" floodColor={activeColor} floodOpacity="0.4" />
          </filter>
          {/* 노브용 아주 옅은 그림자 */}
          <filter id="knobGlow" x="-60%" y="-60%" width="220%" height="220%">
            <feDropShadow dx="0" dy="1.5" stdDeviation="2" floodColor="#0F172A" floodOpacity="0.10" />
          </filter>
        </defs>

        {/* 배경 가이드 호: 옅게 남겨두고, 그 위에 블록 3개가 얹히는 형태 */}
        <path d="M 35 118 A 95 95 0 0 1 225 118" fill="none" stroke="#F8FAFC" strokeWidth="9" strokeLinecap="round" />

        {/* 호 위에 얹힌 3개의 둥근 직사각형 블록: 기준 위치(호의 왼쪽 끝)에 세로로 그려두고
            레벨별 각도(30/90/150)만큼 호의 중심을 축으로 회전시켜 배치 — SummaryHeroView의
            GAUGE_SEGMENTS(각도 계산으로 원 위의 위치를 구하는) 방식과 같은 아이디어를,
            아크 path 대신 회전된 rect로 구현한 버전 */}
        {LEVEL_ORDER.map((lvl) => {
          const isActive = lvl === level;
          return (
            <rect
              key={lvl}
              className="gauge-block"
              x={ARC_LEFT_X - BLOCK_THICKNESS / 2}
              y={ARC_LEFT_Y - BLOCK_LENGTH / 2}
              width={BLOCK_THICKNESS}
              height={BLOCK_LENGTH}
              rx={BLOCK_CORNER_RADIUS}
              fill={isActive ? STATUS_COLORS[lvl] : INACTIVE_COLOR}
              opacity={isActive ? 1 : 0.55}
              filter={isActive ? "url(#activeBlockGlow)" : undefined}
              style={{
                transform: `rotate(${BLOCK_ANGLES[lvl]}deg)`,
                transformOrigin: `${CX}px ${CY}px`,
                transition: "fill 0.35s ease, opacity 0.35s ease",
              }}
            />
          );
        })}

        {/* 노브: 활성 블록 위치를 부드럽게 가리키는 작은 포인터 (회전 중심은 호의 중심점 CX,CY) */}
        <g
          className="ref-gauge-knob"
          style={{
            transform: `rotate(${knobRotateAngle}deg)`,
            transformOrigin: `${CX}px ${CY}px`,
            transition: "transform 0.6s cubic-bezier(0.22, 1, 0.36, 1)",
          }}
        >
          <circle cx={ARC_LEFT_X} cy={ARC_LEFT_Y} r="5.5" fill="#FFFFFF" stroke={activeColor} strokeWidth="2.5" filter="url(#knobGlow)" />
        </g>

        {/* 이모지 / 로고 넣을 자리 (나중에 교체용): 지금은 자리만 확보 */}
        <g className="ref-gauge-emoji-slot" aria-hidden="true">
          {/* 실제로 넣을 땐 아래 중 하나로 교체
            <image href="/path/to/logo.svg" x={CX - 14} y={CY - 40} width="28" height="28" />
            또는
            <text x={CX} y={CY - 26} textAnchor="middle" dominantBaseline="middle" fontSize="22">😊</text>
          */}
        </g>
      </svg>

      <div className="ref-gauge-inner-center" aria-hidden="true">
        {/* 이모지/로고 넣을 자리: 지금은 빈 자리만 확보, 나중에 여기에 <span>😊</span> 등으로 교체 가능 */}
        <div style={{ width: 22, height: 22, marginBottom: 2 }} aria-hidden="true" />
        <span className="ref-count-guide">검토 필요</span>
        <span className="ref-count-num" style={{ color: activeColor, transition: "color 0.35s ease" }}>
          {animatedCount}
        </span>
      </div>

      <div className="ref-bottom-labels-3stage" aria-hidden="true">
        <span className="lbl-step-left" style={{ opacity: level === "SAFE" ? 1 : 0.4, transition: "opacity 0.3s ease" }}>
          안심
        </span>
        <span style={{ color: "#D97706", opacity: level === "CAUTION" ? 1 : 0.4, transition: "opacity 0.3s ease" }}>
          검토
        </span>
        <span className="lbl-step-right" style={{ opacity: level === "REVIEW" ? 1 : 0.4, transition: "opacity 0.3s ease" }}>
          주의
        </span>
      </div>
    </div>
  );
}
