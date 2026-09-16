import React from 'react';

export const AirplaneAnimation: React.FC = () => {
  const airplaneUrl =
    typeof chrome !== 'undefined' && chrome.runtime?.getURL
      ? chrome.runtime.getURL('icons/3d_airplane.png')
      : '/icons/3d_airplane.png';

  return (
    <div className="airplane-anim-stage">
      <style>{`
        .airplane-anim-stage {
          position: relative;
          width: 140px;
          height: 90px;
          display: flex;
          align-items: center;
          justify-content: center;
          overflow: visible;
        }

        /* 3D 비행기 본체: 공중에 부유하며 약간 기우는 모션 */
        .airplane-body-wrap {
          position: relative;
          z-index: 2;
          width: 78px;
          height: 78px;
          animation: airplaneFloat 2.6s ease-in-out infinite alternate;
        }

        .airplane-img {
          width: 100%;
          height: 100%;
          object-fit: contain;
          filter: drop-shadow(0 12px 18px rgba(15, 23, 42, 0.1));
        }

        @keyframes airplaneFloat {
          0% {
            transform: translateY(0px) rotate(-2deg);
          }
          100% {
            transform: translateY(-8px) rotate(3deg);
          }
        }

        /* ★ 가로 수평 제트 기류(Wind Stream) 컨테이너 ★ */
        .wind-stream-container {
          position: absolute;
          left: -8px; /* 비행기 꼬리 뒤편 위치 */
          top: 50%;
          transform: translateY(-50%);
          width: 65px;
          height: 40px;
          z-index: 1;
          pointer-events: none;
        }

        /* 가로 방향으로 뻗어나가는 개별 기류선 (세로가 아닌 확실한 가로 형태) */
        .wind-line {
          position: absolute;
          height: 3px; /* 얇은 가로선 두께 */
          border-radius: 9999px;
          background: linear-gradient(
            270deg,
            rgba(93, 217, 193, 0.8) 0%,
            rgba(93, 217, 193, 0.3) 60%,
            rgba(93, 217, 193, 0) 100%
          );
          transform-origin: right center;
          animation: windFlowHorizontal 1.1s cubic-bezier(0.4, 0, 0.2, 1) infinite;
        }

        /* 위쪽 기류선 (가로) */
        .wind-1 {
          top: 8px;
          right: 0;
          width: 48px;
          animation-delay: 0s;
        }

        /* 중앙 메인 기류선 (가장 긴 가로선) */
        .wind-2 {
          top: 18px;
          right: 4px;
          width: 58px;
          height: 3.5px;
          background: linear-gradient(
            270deg,
            rgba(45, 212, 191, 0.95) 0%,
            rgba(45, 212, 191, 0.4) 65%,
            rgba(45, 212, 191, 0) 100%
          );
          animation-delay: 0.25s;
        }

        /* 아래쪽 기류선 (가로) */
        .wind-3 {
          top: 28px;
          right: 2px;
          width: 38px;
          animation-delay: 0.45s;
        }

        /* 비행기 뒤로 수평 방출되며 사라지는 가로 애니메이션 */
        @keyframes windFlowHorizontal {
          0% {
            transform: translateX(10px) scaleX(0.2);
            opacity: 0;
          }
          30% {
            opacity: 0.9;
          }
          70% {
            transform: translateX(-22px) scaleX(1);
            opacity: 0.7;
          }
          100% {
            transform: translateX(-40px) scaleX(0.7);
            opacity: 0;
          }
        }
      `}</style>

      {/* 1. 비행기 꼬리 뒤에서 가로(수평)로 분사되는 제트 바람선 */}
      <div className="wind-stream-container">
        <div className="wind-line wind-1" />
        <div className="wind-line wind-2" />
        <div className="wind-line wind-3" />
      </div>

      {/* 2. 3D 비행기 본체 */}
      <div className="airplane-body-wrap">
        <img src={airplaneUrl} alt="3D Airplane" className="airplane-img" />
      </div>
    </div>
  );
};
