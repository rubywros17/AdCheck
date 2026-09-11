// src/sidepanel/components/animations/ReviewAnimation.tsx
import React from "react";

export function ReviewAnimation() {
  return (
    <div className="anim-box anim-review">
      {/* 별점 리뷰 말풍선 */}
      <div className="review-bubble">
        <div className="star-row">
          <span>★</span><span>★</span><span>★</span><span>★</span><span>★</span>
        </div>
        <div className="quote-line" />
      </div>
      {/* 콩닥콩닥 주의 느낌표 뱃지 */}
      <div className="review-badge-warn">
        <span>!</span>
      </div>
    </div>
  );
}
