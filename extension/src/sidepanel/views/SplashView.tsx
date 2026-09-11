import React, { useEffect } from "react";

interface Props {
  onComplete?: () => void;
}

export function AdCheckIntroScanner({ onComplete }: Props) {
  useEffect(() => {
    if (!onComplete) return;
    // 2.2초 동안 여유롭게 한 번 스캔한 뒤, 2.8초 시점에 다음 화면으로 이동
    const timer = window.setTimeout(onComplete, 2800);
    return () => window.clearTimeout(timer);
  }, [onComplete]);

  return (
    <div className="toss-viewport">
      <div className="intro-scanner-wrap">

        {/* 🐻 1. 곰돌이 캐릭터 & 양쪽 눈에서 뻗어나가는 레이저 빔 */}
        <div
          className="laser-bear-wrap-clean"
          role="img"
          aria-label="입체 곰돌이 눈에서 빔을 쏘며 AdCheck 로고를 스캔 중"
        >
          <span className="laser-bear-shadow" aria-hidden="true" />
          <div className="laser-bear-face" aria-hidden="true">
            <img src="/icons/adcheck_icon.png" alt="" className="laser-bear-logo-img" />
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

        {/* 🔤 2. 곰돌이가 스캔할 대상: 영문 "AdCheck" 로고 글씨 (박스/선 없이 텍스트만) */}
        <div className="scanned-adcheck-logo-wrap">
          <div className="scanned-adcheck-logo">
            <span className="scanned-brand-ad">Ad</span>
            {/* 캡처 원본 실측 아쿠아-틸 글래스 그라데이션 */}
            <span className="scanned-brand-check">Check</span>
          </div>
          {/* 빔이 닿는 순간 글자를 좌→우로 훑는 컬러 시머 (세로줄 대신) */}
          <div className="logo-shimmer-overlay" aria-hidden="true">
            AdCheck
          </div>
        </div>

        {/* 하단 서브 카피 (필요 시 노출) */}
        <p className="intro-scanner-sub">
          안심할 수 있는 광고만 꼼꼼하게
        </p>

      </div>
    </div>
  );
}
