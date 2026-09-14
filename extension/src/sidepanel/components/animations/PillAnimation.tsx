// src/sidepanel/components/animations/PillAnimation.tsx
import React from "react";

export function PillAnimation() {
  return (
    <div className="pill-3d-stage">
      {/* 바닥에 맺히는 부드러운 그림자 */}
      <div className="pill-3d-shadow" />

      {/* 3D 렌더링 알약 돋보기 검사 이미지 */}
      <img
        src="/icons/3d_pill.png"
        alt="돋보기로 확인하는 3D 캡슐"
        width={74}
        height={88}
        className="pill-3d-img"
      />
    </div>
  );
}
