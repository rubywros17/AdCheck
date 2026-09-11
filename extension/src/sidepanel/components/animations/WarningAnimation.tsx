// src/sidepanel/components/animations/WarningAnimation.tsx
import React from "react";

export function WarningAnimation() {
  return (
    <div className="anim-box anim-warning">
      {/* 바닥 문서 */}
      <div className="doc-paper">
        <div className="doc-line l-long" />
        <div className="doc-line l-mid" />
        <div className="doc-line l-short" />
      </div>
      {/* 통! 튕기며 찍히는 로즈 코랄 ❌ 스탬프 */}
      <div className="stamp-badge">
        <span>✕</span>
      </div>
    </div>
  );
}
