// src/sidepanel/components/animations/ShieldAnimation.tsx
import React from "react";

export function ShieldAnimation() {
  return (
    <div className="shield-3d-stage">
      {/* 바닥에 맺히는 부드러운 그림자 */}
      <div className="shield-3d-shadow" />

      {/* 3D 렌더링 방패 체크 이미지 */}
      <img
        src="/icons/3d_shield.png"
        alt="체크 표시가 있는 방패"
        width={84}
        height={110}
        className="shield-3d-img"
      />
    </div>
  );
}
