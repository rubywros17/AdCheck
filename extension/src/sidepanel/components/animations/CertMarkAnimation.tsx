// src/sidepanel/components/animations/CertMarkAnimation.tsx
import React from "react";

// 실제 건강기능식품 인증마크 이미지 전용 그래픽.
// "인증마크로 확인하세요" 류의 팁에서만 사용해, 소비자가 실제로 찾아야 할 마크 모양을 보여줍니다.
export function CertMarkAnimation() {
  return (
    <div className="health-mark-badge">
      <img
        src="/icons/health_food_mark.png"
        alt="건강기능식품 인증마크"
        width={96}
        height={96}
        className="health-mark-img"
      />
      {/* 표면을 사선으로 스쳐 지나가는 쉬머 광택 */}
      <span className="health-mark-shimmer" aria-hidden="true" />
    </div>
  );
}
