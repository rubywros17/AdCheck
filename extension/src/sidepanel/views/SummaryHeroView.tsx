import React, { useEffect, useId, useState } from 'react';

// ==========================================
// 결과 안내 화면 게이지(건수) 화면
// ==========================================
interface SmartDialGaugeProps {
  score: number;
  maxScore?: number;
  label?: string;
  isSafe: boolean;
}

const SmartDialGauge: React.FC<SmartDialGaugeProps> = ({
  score,
  maxScore = 10,
  label = '검토 필요',
  isSafe,
}) => {
  const gradientId = useId();
  const size = 240;
  const height = 216;
  const strokeWidth = 4.5;
  const radius = 108;
  const center = size / 2;
  const innerDiameter = 152;

  // 위쪽을 감싸는 200도 아크: 양 끝은 수평선에서 10도만 내려옵니다.
  const totalAngle = 200;
  const startAngle = 270 - totalAngle / 2;
  const endAngle = 270 + totalAngle / 2;
  const startRadians = (startAngle * Math.PI) / 180;
  const endRadians = (endAngle * Math.PI) / 180;
  const startX = center + radius * Math.cos(startRadians);
  const startY = center + radius * Math.sin(startRadians);
  const endX = center + radius * Math.cos(endRadians);
  const endY = center + radius * Math.sin(endRadians);
  const labelGap = 20;
  const arcPath = `M ${startX} ${startY} A ${radius} ${radius} 0 1 1 ${endX} ${endY}`;

  const ratio = Math.min(Math.max(score / maxScore, 0), 1);
  const statusColor = isSafe ? '#10B981' : '#F43F5E';
  const knobColor = isSafe ? '#10B981' : '#F43F5E';

  // 화면이 열릴 때 왼쪽 끝(0)에서 실제 결과 지점까지 부드럽게 미끄러지는 노브 모션
  const [animatedRatio, setAnimatedRatio] = useState(0);

  useEffect(() => {
    let targetFrame = 0;
    const startFrame = requestAnimationFrame(() => {
      targetFrame = requestAnimationFrame(() => {
        setAnimatedRatio(ratio);
      });
    });

    return () => {
      cancelAnimationFrame(startFrame);
      cancelAnimationFrame(targetFrame);
    };
  }, [ratio]);

  return (
    <div
      style={{
        position: 'relative',
        width: size,
        height,
        margin: '0 auto',
      }}
    >
      {/* 1. AdCheck 시그니처 민트 후광 -> 노브가 진행되는 만큼 코랄 레드로 자연스럽게 블렌딩 */}
      <div
        style={{
          position: 'absolute',
          top: center,
          left: center,
          transform: 'translate(-50%, -50%)',
          width: radius * 2,
          height: radius * 2,
          borderRadius: '50%',
          background: 'radial-gradient(circle, rgba(16, 185, 129, 0.4) 0%, rgba(16, 185, 129, 0) 70%)',
          opacity: 1 - animatedRatio,
          filter: 'blur(28px)',
          zIndex: 0,
          pointerEvents: 'none',
          transition: 'opacity 1.2s cubic-bezier(0.16, 1, 0.3, 1)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          top: center,
          left: center,
          transform: 'translate(-50%, -50%)',
          width: radius * 2,
          height: radius * 2,
          borderRadius: '50%',
          background: 'radial-gradient(circle, rgba(244, 63, 94, 0.42) 0%, rgba(244, 63, 94, 0) 70%)',
          opacity: animatedRatio,
          filter: 'blur(28px)',
          zIndex: 0,
          pointerEvents: 'none',
          transition: 'opacity 1.2s cubic-bezier(0.16, 1, 0.3, 1)',
        }}
      />

      {/* 2. 외곽 아치 게이지 트랙 SVG (유리알 너머로 영롱하게 퍼지는 앰비언트 글로우 포함) */}
      <svg
        aria-hidden="true"
        width={size}
        height={height}
        viewBox={`0 0 ${size} ${height}`}
        style={{
          display: 'block',
          position: 'relative',
          zIndex: 1,
          filter: `drop-shadow(0 0 16px ${isSafe ? 'rgba(16, 185, 129, 0.45)' : 'rgba(244, 63, 94, 0.45)'})`,
        }}
      >
        <defs>
          {/* AdCheck 시그니처 민트 -> 웜 앰버 -> 로즈 코랄 그라데이션 */}
          <linearGradient
            id={gradientId}
            gradientUnits="userSpaceOnUse"
            x1={center - radius}
            y1={center}
            x2={center + radius}
            y2={center}
          >
            <stop offset="0%" stopColor="#10B981" />
            <stop offset="50%" stopColor="#F59E0B" />
            <stop offset="100%" stopColor="#F43F5E" />
          </linearGradient>
        </defs>

        {/* 아주 연한 소프트 그레이 배경 트랙 */}
        <path
          d={arcPath}
          fill="none"
          stroke="#F1F5F9"
          strokeWidth={strokeWidth}
          strokeLinecap="round"
        />

        {/* 색상 눈금은 고정하고 포인터가 왼쪽에서 오른쪽으로 이동 */}
        <path
          d={arcPath}
          fill="none"
          stroke={`url(#${gradientId})`}
          strokeWidth={strokeWidth}
          strokeLinecap="round"
        />

        {/* 3. 게이지 끝단 포인터 노브 (동그란 점, 왼쪽 끝에서 결과 위치까지 슬라이딩) */}
        <g
          className="smart-dial-pointer"
          style={{
            transform: `rotate(${animatedRatio * totalAngle}deg)`,
            transformOrigin: `${center}px ${center}px`,
            transition: 'transform 1.2s cubic-bezier(0.16, 1, 0.3, 1)',
          }}
        >
          <circle
            cx={startX}
            cy={startY}
            r={5.5}
            fill="#FFFFFF"
            stroke={knobColor}
            strokeWidth={2.5}
            style={{
              filter: 'drop-shadow(0 2px 3px rgba(148, 163, 184, 0.16))',
            }}
          />
        </g>
      </svg>

      {/* 4. 백라이트 글로우 + 초영롱한 크리스탈 글래스모피즘 중앙 다이얼 */}
      <div
        className="gauge-dial-container"
        style={{
          position: 'absolute',
          top: center,
          left: center,
          transform: 'translate(-50%, -50%)',
          width: innerDiameter,
          height: innerDiameter,
          zIndex: 2,
        }}
      >
        {/* 🌟 흰색 원 뒤편(바깥)에서만 360도로 번지는 백라이트: 0건일 땐 아쿠아 민트, 채워질수록 로즈+앰버로 크로스페이드 */}
        <div
          className="dial-backlight-glow"
          aria-hidden="true"
          style={{
            background:
              'radial-gradient(circle, rgba(45, 212, 191, 0.45) 0%, rgba(45, 212, 191, 0.25) 40%, transparent 70%)',
            opacity: 1 - animatedRatio,
            transition: 'opacity 1.8s cubic-bezier(0.16, 1, 0.3, 1)',
          }}
        />
        <div
          className="dial-backlight-glow"
          aria-hidden="true"
          style={{
            background:
              'radial-gradient(circle, rgba(244, 63, 94, 0.45) 0%, rgba(245, 158, 11, 0.25) 40%, transparent 70%)',
            opacity: animatedRatio,
            transition: 'opacity 1.8s cubic-bezier(0.16, 1, 0.3, 1)',
          }}
        />

        {/* 💎 전면 다이얼: 빛이 안쪽으로 침범하지 않는 깨끗한 솔리드 화이트 */}
        <div
          className="summary-hero-dial"
          style={{
            position: 'relative',
            width: '100%',
            height: '100%',
            borderRadius: '50%',
            background: '#FFFFFF',
            border: '1px solid rgba(226, 232, 240, 0.8)',
            boxShadow: '0 8px 20px rgba(15, 23, 42, 0.06)',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '18px 16px 24px',
            boxSizing: 'border-box',
            zIndex: 1,
          }}
        >
          {/* 상단 라벨 */}
          <span
            style={{
              fontSize: '12px',
              fontWeight: 700,
              color: statusColor,
              letterSpacing: '-0.2px',
              margin: 0,
            }}
          >
            {label}
          </span>

          {/* 메인 숫자: 부담스럽지 않은 세련된 크기 */}
          <span
            style={{
              fontSize: '38px',
              fontWeight: 800,
              color: '#0F172A',
              fontFamily: 'system-ui, -apple-system, sans-serif',
              letterSpacing: '-1px',
              lineHeight: 1,
              margin: 0,
              marginTop: '4px',
            }}
          >
            {score}
          </span>
        </div>
      </div>

      {/* 5. 양 끝점 바로 아래에 중심을 맞춘 눈금 라벨 */}
      <div
        style={{
          position: 'absolute',
          top: startY + labelGap,
          left: startX,
          transform: 'translateX(-50%)',
          fontSize: '11px',
          fontWeight: 500,
          lineHeight: 1.4,
          whiteSpace: 'nowrap',
          color: '#10B981',
          zIndex: 2,
        }}
      >
        안심
      </div>
      <div
        style={{
          position: 'absolute',
          top: endY + labelGap,
          left: endX,
          transform: 'translateX(-50%)',
          fontSize: '11px',
          fontWeight: 500,
          lineHeight: 1.4,
          whiteSpace: 'nowrap',
          color: '#F43F5E',
          zIndex: 2,
        }}
      >
        검토
      </div>
    </div>
  );
};

// ==========================================
// SummaryHeroView 화면 뷰
// ==========================================
export interface SummaryHeroViewProps {
  [key: string]: any;
}

export const SummaryHeroView: React.FC<SummaryHeroViewProps> = (props) => {
  const handleNext =
    props.onNext ||
    props.onConfirm ||
    props.onGoNext ||
    props.onGoDetail ||
    props.onClickNext ||
    props.onContinue ||
    (() => {});

  const displayCount = props.score ?? props.count ?? props.issueCount ?? 10;
  const isSafe = props.level ? props.level === 'SAFE' : displayCount === 0;
  const label = props.label ?? (isSafe ? '안심' : '검토 필요');

  return (
    <div
      className="tab-panel"
      style={{ width: '100%', animation: 'fadeIn 0.3s ease' }}
    >
      <div
        className="toss-hero-box"
        style={{
          textAlign: 'center',
          padding: '24px 20px 22px',
        }}
      >
        {/* 상단 안내 타이틀 */}
        <div style={{ marginBottom: '18px' }}>
          <h2
            style={{
              fontSize: '21px',
              fontWeight: 800,
              lineHeight: 1.35,
              color: '#0F172A',
              margin: '0 0 6px 0',
              letterSpacing: '-0.5px',
              fontFamily: '-apple-system, BlinkMacSystemFont, "Pretendard", "Segoe UI", "Apple SD Gothic Neo", sans-serif',
            }}
          >
            광고 검토 결과 안내
          </h2>
          <p
            style={{
              fontSize: '13px',
              color: '#64748B',
              margin: 0,
              lineHeight: 1.45,
              wordBreak: 'keep-all',
            }}
          >
            {isSafe
              ? '검토가 필요한 의심 문구가 발견되지 않았어요.'
              : '소비자가 오인하거나 과장될 우려가 있는 표현이에요.'}
          </p>
        </div>

        {/* 첫 번째 레퍼런스 스타일 스마트 다이얼 게이지 */}
        <div style={{ margin: '8px 0 22px' }}>
          <SmartDialGauge
            score={displayCount}
            maxScore={10}
            label={label}
            isSafe={isSafe}
          />
        </div>

        {/* 확인 버튼 */}
        <button
          type="button"
          onClick={handleNext}
          className="toss-btn-primary"
          style={{
            width: '100%',
            height: '48px',
            borderRadius: '16px',
            backgroundColor: '#0F172A',
            color: '#FFFFFF',
            fontSize: '15px',
            fontWeight: 500,
            letterSpacing: '-0.2px',
            border: 'none',
            cursor: 'pointer',
            boxShadow: '0 4px 12px rgba(15, 23, 42, 0.15)',
            transition: 'all 0.2s ease',
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.backgroundColor = '#1E293B';
            e.currentTarget.style.transform = 'translateY(-1px)';
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.backgroundColor = '#0F172A';
            e.currentTarget.style.transform = 'translateY(0)';
          }}
        >
          {isSafe ? '다시 검사하기' : '어떤 문구인지 확인하기'}
        </button>
      </div>
    </div>
  );
};

export default SummaryHeroView;
