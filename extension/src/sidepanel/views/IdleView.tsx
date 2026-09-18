//"이 상품 광고 믿고 사도 될까요?" 버튼 있는 첫 화면

interface Props {
  variant?: "IDLE" | "ERROR" | "UNSUPPORTED";
  onAnalyze: () => void;
}

export function IdleView({ variant = "IDLE", onAnalyze }: Props) {
  if (variant === "ERROR") {
    return (
      <div className="toss-hero-box">
        <div className="hero-icon-circle icon-circle-red">
          <svg
            width={32}
            height={32}
            viewBox="0 0 24 24"
            fill="none"
            stroke="#EF4444"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
        </div>
        <h2 className="hero-title">
          분석 서버에<br />
          연결할 수 없어요
        </h2>
        <p className="hero-sub hero-sub-spacious">잠시 후 다시 시도해주세요.</p>
        <button className="btn-brand-primary btn-idle-margin" type="button" onClick={onAnalyze}>
          다시 시도
        </button>
      </div>
    );
  }

  if (variant === "UNSUPPORTED") {
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
        <h2 className="hero-title hero-title-unsupported">
          현재 페이지는<br />
          분석할 수 없어요
        </h2>
        <p className="hero-sub hero-sub-spacious hero-sub-unsupported">상품 상세페이지에서 다시 실행해 주세요.</p>
        {/* 홈으로 돌아가는 대신, 현재 탭을 바로 재분석 */}
        <button className="btn-brand-primary btn-idle-margin btn-idle-margin-lg" type="button" onClick={onAnalyze}>
          다시 분석하기
        </button>
      </div>
    );
  }

  return (
    <div className="toss-hero-box stagger-entry">
      <h2 className="hero-title hero-title-idle">
        이 상품 광고,<br />
        <span className="text-dark">믿고 사도 될까요?</span>
      </h2>
      <p className="hero-sub hero-sub-spacious">
        식약처 공식 기능성 인정 기준과<br />
        상세페이지 광고 표현을 실시간 비교해드려요.
      </p>
      <button className="btn-brand-primary btn-idle-margin" type="button" onClick={onAnalyze}>
        현재 페이지 광고 점검하기
      </button>
    </div>
  );
}
