// src/sidepanel/components/animations/PillAnimation.tsx
import React from "react";

export function PillAnimation() {
  return (
    <div className="pill-3d-stage">
      {/* 바닥에 맺히는 부드러운 그림자 */}
      <div className="pill-3d-shadow" />

      {/* 3D 렌더링 알약 이미지 */}
      <img
        src="/icons/pill_3d.png"
        alt="3D 영양제 캡슐"
        width="72"
        height="72"
        className="pill-3d-img"
      />
    </div>
  );
}
