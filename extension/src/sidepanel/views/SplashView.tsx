import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';

interface SplashViewProps {
  onFinish?: () => void;
}

export const SplashView: React.FC<SplashViewProps> = ({ onFinish }) => {
  const [animating, setAnimating] = useState(false);
  const logoGroupRef = useRef<SVGGElement>(null);
  const [logoOffsetX, setLogoOffsetX] = useState(0);

  useEffect(() => {
    // 0.2초 후 스캔 시작
    const startTimer = setTimeout(() => setAnimating(true), 200);
    // 2.8초 후 대기 화면으로 전환
    const finishTimer = setTimeout(() => {
      if (onFinish) onFinish();
    }, 2800);

    return () => {
      clearTimeout(startTimer);
      clearTimeout(finishTimer);
    };
  }, [onFinish]);

  // "AdCheck" 텍스트 전체의 실제 렌더링 너비를 측정해 곰돌이 중심(x=160)에 정확히 맞춥니다.
  // 폰트마다 실제 글자 폭이 달라서 좌표를 하드코딩하면 어긋나므로, 실측 후 보정합니다.
  useLayoutEffect(() => {
    if (!logoGroupRef.current) return;
    const bbox = logoGroupRef.current.getBBox();
    const textCenter = bbox.x + bbox.width / 2;
    setLogoOffsetX(160 - textCenter);
  }, []);

  const bearIconUrl =
    typeof chrome !== 'undefined' && chrome.runtime?.getURL
      ? chrome.runtime.getURL('icons/adcheck_icon.png')
      : '/icons/adcheck_icon.png';

  return (
    <div className="splash-fullscreen-container">
      <style>{`
        .splash-fullscreen-container {
          width: 100%;
          height: 100vh;
          background: #FFFFFF;
          display: flex;
          align-items: center;
          justify-content: center;
          overflow: hidden;
          user-select: none;
        }

        .splash-svg-stage {
          width: 320px;
          height: 240px;
          overflow: visible;
        }

        /* 1. 곰돌이 눈 발광 */
        .bear-eye-glow {
          opacity: 0;
          transition: opacity 0.3s ease;
        }
        .anim-active .bear-eye-glow {
          opacity: 1;
        }

        /* 2. 두 눈을 각각의 축으로 고정한 채 A(좌측) -> k(우측)로 회전하며 훑는 스포트라이트 광선 2개.
           (translateX 대신 각 눈 좌표를 축으로 회전만 시켜서, 빔의 꼭짓점이 계속 각 눈 위치에 고정됩니다.
           transform-origin은 두 눈이 서로 달라 인라인 스타일로 개별 지정하고, 애니메이션 자체는 공유합니다.) */
        .laser-light-cone {
          opacity: 0;
        }
        .anim-active .laser-light-cone {
          animation: sweepLeftToRight 1.6s cubic-bezier(0.3, 0, 0.25, 1) forwards;
        }

        /* 눈 위치를 축으로 회전: A쪽(30deg) -> k쪽(-40deg)
           (원뿔의 꼭짓점이 회전축과 같은 지점이라, 부호가 반대일수록 오히려 반대 방향으로 기울어짐.
           30deg가 왼쪽/A, -40deg가 오른쪽/k을 향하도록 실제 좌표 계산으로 검증한 값) */
        @keyframes sweepLeftToRight {
          0% {
            opacity: 0;
            transform: rotate(30deg);
          }
          15% {
            opacity: 0.85;
          }
          85% {
            opacity: 0.85;
          }
          100% {
            opacity: 0;
            transform: rotate(-40deg);
          }
        }

        /* 3. Check 글자 내부 채색 (사각 박스 원천 방지: SVG clipPath 사용) */
        .text-teal-overlay {
          clip-path: url(#checkTextClip);
        }

        #checkColorMaskRect {
          width: 0px; /* 초기에는 0% */
        }
        .anim-active #checkColorMaskRect {
          animation: revealCheckTeal 1.6s cubic-bezier(0.3, 0, 0.25, 1) forwards;
        }

        /* 빛이 Ad(좌측)를 지나 Check에 진입할 때부터 C -> k 순서로 채움 */
        @keyframes revealCheckTeal {
          0% {
            width: 0px;
          }
          30% {
            width: 0px; /* Ad를 훑는 동안은 대기 */
          }
          50% {
            width: 45px; /* C, h 채움 */
          }
          75% {
            width: 90px; /* e, c 채움 */
          }
          100% {
            width: 140px; /* k까지 100% 완벽하게 채움 */
          }
        }
      `}</style>

      <svg
        className={`splash-svg-stage ${animating ? 'anim-active' : ''}`}
        viewBox="0 0 320 240"
      >
        <defs>
          {/* 스캔 광선 그라데이션: 더 옅고 은은하게 (위: 눈동자 백색 -> 아래: 청록색 빛기둥) */}
          <linearGradient id="spotlightGrad" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.55" />
            <stop offset="30%" stopColor="#5EEAD4" stopOpacity="0.4" />
            <stop offset="70%" stopColor="#2DD4BF" stopOpacity="0.18" />
            <stop offset="100%" stopColor="#0D9488" stopOpacity="0" />
          </linearGradient>

          {/* 청록색 Check 글자 그라데이션 */}
          <linearGradient id="checkTealGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#2DD4BF" />
            <stop offset="100%" stopColor="#0D9488" />
          </linearGradient>

          {/* 블러 필터 */}
          <filter id="lightBlur" x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur stdDeviation="5" />
          </filter>

          {/* ★ 핵심: 오직 Check 글자 내부에만 색이 차오르도록 제한하는 마스크 ★ */}
          <clipPath id="checkTextClip">
            {/* 이 사각형이 왼쪽에서 오른쪽으로 늘어나면서 글자 색을 드러냄 */}
            <rect id="checkColorMaskRect" x="156" y="150" height="50" />
          </clipPath>
        </defs>

        {/* ─── 1. 곰돌이 캐릭터 (중앙 정렬, 살짝 작게) ─── */}
        <image
          href={bearIconUrl}
          x="123"
          y="23"
          width="74"
          height="74"
        />

        {/* 곰돌이 눈동자 발광 점 */}
        <circle className="bear-eye-glow" cx="149" cy="64" r="3" fill="#5EEAD4" filter="url(#lightBlur)" />
        <circle className="bear-eye-glow" cx="149" cy="64" r="1.5" fill="#FFFFFF" />
        <circle className="bear-eye-glow" cx="171" cy="64" r="3" fill="#5EEAD4" filter="url(#lightBlur)" />
        <circle className="bear-eye-glow" cx="171" cy="64" r="1.5" fill="#FFFFFF" />

        {/* ─── 2. 스캔 광선 레이어: 곰돌이보다 나중에 그려서 곰돌이 위로 겹치게 함
             (왼쪽 눈 X=149, 오른쪽 눈 X=171, 둘 다 Y=64에서 각각 출발) ─── */}
        <g className="laser-light-cone" style={{ transformOrigin: '149px 64px' }}>
          {/* 부드럽게 퍼지는 메인 스포트라이트 삼각 빔 */}
          <polygon
            points="149,64 129,200 169,200"
            fill="url(#spotlightGrad)"
            filter="url(#lightBlur)"
          />
          {/* 중심 코어 빛줄기 */}
          <polygon
            points="149,64 138,200 160,200"
            fill="url(#spotlightGrad)"
            opacity="0.5"
          />
        </g>
        <g className="laser-light-cone" style={{ transformOrigin: '171px 64px' }}>
          {/* 부드럽게 퍼지는 메인 스포트라이트 삼각 빔 */}
          <polygon
            points="171,64 151,200 191,200"
            fill="url(#spotlightGrad)"
            filter="url(#lightBlur)"
          />
          {/* 중심 코어 빛줄기 */}
          <polygon
            points="171,64 160,200 182,200"
            fill="url(#spotlightGrad)"
            opacity="0.5"
          />
        </g>

        {/* ─── 3. AdCheck 로고 텍스트 (사각 박스 원천 불가한 SVG 텍스트) ───
             실측 너비 기반으로 translateX(logoOffsetX)만큼 이동해 곰돌이 중심(x=160)에 정확히 맞춥니다. */}
        <g
          id="brandLogoText"
          ref={logoGroupRef}
          transform={`translate(${logoOffsetX}, 0)`}
          style={{ fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif', fontWeight: 800 }}
        >
          {/* 'Ad' - 고정 딥 네이비 */}
          <text x="102" y="190" fontSize="38" fill="#0B192C" letterSpacing="-0.5">
            Ad
          </text>

          {/* 'Check' - 밑바탕 옅은 회색 */}
          <text x="156" y="190" fontSize="38" fill="#CBD5E1" letterSpacing="-0.5">
            Check
          </text>

          {/* 'Check' - 빛이 지나가며 차오르는 청록색 레이어 (오직 글자 형태만 마스킹됨) */}
          <g className="text-teal-overlay">
            <text x="156" y="190" fontSize="38" fill="url(#checkTealGrad)" letterSpacing="-0.5">
              Check
            </text>
          </g>
        </g>
      </svg>
    </div>
  );
};
