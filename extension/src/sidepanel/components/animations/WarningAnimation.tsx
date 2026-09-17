// src/sidepanel/components/animations/WarningAnimation.tsx
import React from "react";

export function WarningAnimation() {
  return (
    <div className="warning-3d-stage">
      {/* 바닥에 맺히는 부드러운 그림자 */}
      <div className="warning-3d-shadow" />

      {/* 3D 렌더링 문서 + 경고(X) 스탬프 이미지 */}
      <img
        src="/icons/3d_warning.png"
        alt="문제 문구가 표시된 문서"
        width={59}
        height={92}
        className="warning-3d-img"
      />
    </div>
  );
}
