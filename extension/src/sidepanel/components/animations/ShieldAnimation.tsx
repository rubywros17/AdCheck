// src/sidepanel/components/animations/ShieldAnimation.tsx
import React from "react";

export function ShieldAnimation() {
  return (
    <div className="anim-box anim-shield">
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
    </div>
  );
}
