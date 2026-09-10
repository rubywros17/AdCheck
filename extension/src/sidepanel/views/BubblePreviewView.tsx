import type { FindingWithKeyword } from "../types";

interface Props {
  findings: FindingWithKeyword[];
  onBack: () => void;
  onSelect: (finding: FindingWithKeyword, idx: number) => void;
  onShowAll: () => void;
}

export function BubblePreviewView({ findings, onBack, onSelect, onShowAll }: Props) {
  return (
    <div className="toss-hero-box bubble-preview-box">
      <div className="unified-step-bar">
        <button type="button" className="btn-step-back" onClick={onBack} aria-label="이전 단계로">
          ←
        </button>
      </div>
      <h2 className="hero-title hero-title-giant hero-title-result">
        주요 문구를 먼저 살펴볼까요?
      </h2>
      <p className="hero-sub hero-sub-clean">
        궁금한 문구를 눌러보면<br />
        자세한 내용을 확인할 수 있어요.
      </p>
      <div className="bubble-cloud">
        {findings.slice(0, 5).map((finding, idx) => {
          const isDanger = finding.message.includes("의약품");
          return (
            <span
              key={idx}
              className="bubble-entry stagger-entry"
              style={{ animationDelay: `${idx * 0.05}s` }}
            >
              <button
                type="button"
                className={`preview-bubble ${isDanger ? "preview-bubble-disease" : "preview-bubble-guarantee"}`}
                style={{ animationDelay: `${(idx % 4) * 0.3}s` }}
                onClick={() => onSelect(finding, idx)}
              >
                {finding.bubbleLabel}
              </button>
            </span>
          );
        })}
      </div>
      <button className="btn-brand-primary btn-summary-margin" type="button" onClick={onShowAll}>
        전체 목록 보기 →
      </button>
    </div>
  );
}
