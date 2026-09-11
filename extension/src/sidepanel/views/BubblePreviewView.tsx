
//5. 주요 문구 화면	키워드 버블 둥둥 떠다니는 화면
import type { FindingWithKeyword } from "../types";

interface Props {
  findings: FindingWithKeyword[];
  onBack: () => void;
  onSelect: (finding: FindingWithKeyword, idx: number) => void;
  onShowAll: () => void;
}

export function BubblePreviewView({ findings, onSelect, onShowAll }: Props) {
  return (
    <div className="toss-hero-box bubble-preview-box">
      <h2 className="hero-title">
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
