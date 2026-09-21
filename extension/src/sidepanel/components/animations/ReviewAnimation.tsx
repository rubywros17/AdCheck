// src/sidepanel/components/animations/ReviewAnimation.tsx
import React from "react";

export function ReviewAnimation() {
  return (
    <div className="review-3d-stage">
      {/* 바닥에 맺히는 부드러운 그림자 */}
      <div className="review-3d-shadow" />

      {/* 3D 렌더링 별점 리뷰 말풍선 + 주의 뱃지 이미지 */}
      <img
        src="/icons/3d_review.png"
        alt="별점 리뷰 말풍선과 주의 표시"
        width={90}
        height={85}
        className="review-3d-img"
      />
    </div>
  );
}
