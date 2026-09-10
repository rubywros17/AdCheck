import { ReferenceArcGauge } from "../components/ReferenceArcGauge";
import type { ReviewLevel } from "../types";

interface Props {
  count: number;
  level: ReviewLevel;
  onContinue: () => void;
}

export function SummaryHeroView({ count, level, onContinue }: Props) {
  const isSafe = level === "SAFE";

  return (
    <div className={`toss-hero-box ${isSafe ? "safe-box alert-hero-box-safe" : "alert-hero-box"}`}>
      <h2 className="hero-title hero-title-giant hero-title-result">
        {isSafe ? "발견된 주의 표현이 없어요" : "광고 검토 결과 안내"}
      </h2>
      <p className="hero-sub hero-sub-clean">
        {isSafe ? (
          <>
            식약처 공식 기능성 범위를 벗어나거나<br />
            소비자를 오인시킬 수 있는 문구가 확인되지 않았어요.
          </>
        ) : (
          "소비자가 오인하거나 과장될 우려가 있는 표현이에요."
        )}
      </p>
      <ReferenceArcGauge count={count} level={level} />
      <button className="btn-brand-primary btn-summary-margin" type="button" onClick={onContinue}>
        {isSafe ? "다시 점검하기" : "어떤 문구인지 확인하기"}
      </button>
    </div>
  );
}
