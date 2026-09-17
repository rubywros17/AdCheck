import type { ReviewLevel } from "../types";

// 앱 전체 표준 라벨: SAFE=안심 / CAUTION=검토 / REVIEW=주의
// (여러 화면에서 각자 하드코딩하다 라벨이 서로 반대로 꼬였던 적이 있어 여기 하나로 모음)
export const REVIEW_LEVEL_LABEL: Record<ReviewLevel, string> = {
  SAFE: "안심",
  CAUTION: "검토",
  REVIEW: "주의",
};
