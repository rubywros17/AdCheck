//"이 상품 광고 믿고 사도 될까요?" 버튼 있는 첫 화면

// CTA 버튼 바로 위 곰돌이 + 물음표 아이콘. 곰돌이(손 든 포즈)와 물음표를 별도 이미지로 분리해
// 물음표만 둥둥 떠다니는 느낌을 줄 수 있게 함. DetailListView.tsx와 동일한 방식으로 확장 아이콘 경로를 구함
const BEAR_QUESTION_HAND_ICON_URL =
  typeof chrome !== "undefined" && chrome.runtime?.getURL
    ? chrome.runtime.getURL("icons/bear-question%20-%20hand.png")
    : "/icons/bear-question%20-%20hand.png";

const QUESTION_MARK_ICON_URL =
  typeof chrome !== "undefined" && chrome.runtime?.getURL
    ? chrome.runtime.getURL("icons/question-mark.png")
    : "/icons/question-mark.png";

interface Props {
  variant?: "IDLE" | "ERROR" | "UNSUPPORTED";
  onAnalyze: () => void;
  onReset: () => void;
  onGoHome?: () => void;
}

export function IdleView({ variant = "IDLE", onAnalyze, onReset, onGoHome }: Props) {
  if (variant !== "IDLE") {
    const isError = variant === "ERROR";

    return (
      <div className="toss-hero-box">
        <div className={`hero-icon-circle ${isError ? "icon-circle-red" : "icon-circle-gray"}`}>
          <svg
            width={isError ? 32 : 30}
            height={isError ? 32 : 30}
            viewBox="0 0 24 24"
            fill="none"
            stroke={isError ? "#EF4444" : "#64748B"}
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="10" />
            {isError ? (
              <>
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </>
            ) : (
              <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
            )}
          </svg>
        </div>
        <h2 className="hero-title">
          {isError ? "분석 서버에" : "현재 페이지는"}<br />
          {isError ? "연결할 수 없어요" : "분석할 수 없어요"}
        </h2>
        <p className="hero-sub hero-sub-spacious">
          {isError ? "잠시 후 다시 시도해주세요." : "상품 상세페이지에서 다시 실행해 주세요."}
        </p>
        <button
          className="btn-brand-primary btn-idle-margin"
          type="button"
          onClick={isError ? onAnalyze : onReset}
        >
          {isError ? "다시 시도" : "처음으로 돌아가기"}
        </button>
        {isError && onGoHome && (
          <button className="btn-idle-secondary" type="button" onClick={onGoHome}>
            처음으로 돌아가기
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="toss-hero-box toss-hero-box-idle stagger-entry">
      <h2 className="hero-title hero-title-idle">
        이 상품 광고,<br />
        <span className="text-dark">믿고 사도 될까요?</span>
      </h2>
      <p className="hero-sub hero-sub-spacious">
        식약처 공식 기능성 인정 기준과<br />
        상세페이지 광고 표현을 실시간 비교해드려요.
      </p>
      <div className="btn-idle-margin idle-bear-figure">
        <img
          src={BEAR_QUESTION_HAND_ICON_URL}
          alt=""
          aria-hidden="true"
          style={{ width: "75px", height: "auto", display: "block" }}
        />
        <img
          src={QUESTION_MARK_ICON_URL}
          alt=""
          aria-hidden="true"
          className="idle-question-mark-float"
          style={{ position: "absolute", top: "-6px", right: "-30px", width: "30px", height: "auto" }}
        />
      </div>
      <button className="btn-brand-primary btn-idle-cta-lower" type="button" onClick={onAnalyze}>
        현재 페이지 광고 점검하기
      </button>
    </div>
  );
}
