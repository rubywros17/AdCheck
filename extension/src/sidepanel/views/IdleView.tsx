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
  variant?: "IDLE" | "ERROR" | "UNSUPPORTED" | "HISTORY_LOADING" | "HISTORY_ERROR";
  onAnalyze: () => void;
  onReset: () => void;
  onGoHome?: () => void;
}

export function IdleView({ variant = "IDLE", onAnalyze, onReset, onGoHome }: Props) {
  // 점검 기록을 다시 불러오는 중: 실제 백엔드 GET 요청이 보통 1초 안팎으로 끝나는 짧은 대기라,
  // AnalyzingView의 17종 상식 팁 카루셀(수 초짜리 실제 분석용)은 과하다고 판단해 별도 스피너만 둠.
  if (variant === "HISTORY_LOADING") {
    return (
      <div className="toss-hero-box">
        <div className="history-loading-spinner" aria-hidden="true" />
        <p className="hero-sub hero-sub-spacious">점검 기록을 불러오는 중이에요...</p>
      </div>
    );
  }

  // 점검 기록 재사용 TTL 만료(서버가 이미 정리함) 또는 서버 오류로 실제 결과를 못 가져온 경우.
  // "다시 시도"가 아니라 "처음으로 돌아가기"만 주는 이유: 재사용 TTL 만료는 같은 id로 다시
  // 조회해도 절대 복구되지 않는 영구적인 상태라, 재시도 버튼을 주면 사용자를 오도하게 된다.
  if (variant === "HISTORY_ERROR") {
    return (
      <div className="toss-hero-box">
        <div className="hero-icon-circle icon-circle-gray">
          <svg
            width={30}
            height={30}
            viewBox="0 0 24 24"
            fill="none"
            stroke="#64748B"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="10" />
            <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
          </svg>
        </div>
        <h2 className="hero-title">
          점검 기록을 볼 수 없어요
        </h2>
        <p className="hero-sub hero-sub-spacious">
          이 점검 기록은 오래되어 더 이상 볼 수 없어요.<br />다시 분석해보시겠어요?
        </p>
        <button className="btn-brand-primary btn-idle-margin" type="button" onClick={onReset}>
          처음으로 돌아가기
        </button>
      </div>
    );
  }

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
