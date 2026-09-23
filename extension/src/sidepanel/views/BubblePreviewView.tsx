
//5. 주요 문구 화면	키워드 버블 둥둥 떠다니는 화면
import type { CSSProperties } from "react";
import type { FindingWithKeyword } from "../types";
import { getCategoryTheme } from "../../constants/judgmentCategories";

interface Props {
  findings: FindingWithKeyword[];
  onBack: () => void;
  onSelect: (finding: FindingWithKeyword, idx: number) => void;
  onShowAll: () => void;
}

export function BubblePreviewView({ findings, onBack, onSelect, onShowAll }: Props) {
  return (
    <div className="toss-hero-box bubble-preview-box">
      {/* 화살표(좌) / 제목(중앙) / 여백(우)을 3칸 그리드로 나눠, 제목이 길어져도 화살표와 절대 겹치지 않게 함 */}
      <div style={{ display: 'grid', gridTemplateColumns: '22px 1fr 22px', alignItems: 'center', columnGap: '6px', width: '100%', marginBottom: '8px' }}>
        <button type="button" className="icon-back-btn" onClick={onBack} aria-label="뒤로가기">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#0F172A" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
        <h2 className="hero-title" style={{ gridColumn: 2, margin: 0 }}>
          주요 문구를 먼저 살펴볼까요?
        </h2>
      </div>
      <p className="hero-sub hero-sub-clean">
        궁금한 문구를 눌러보면<br />
        자세한 내용을 확인할 수 있어요.
      </p>
      <div className="bubble-cloud">
        {findings.slice(0, 5).map((finding, idx) => {
          const theme = getCategoryTheme(finding.category);
          return (
            <span
              key={idx}
              className="bubble-entry stagger-entry"
              style={{ animationDelay: `${idx * 0.05}s` }}
            >
              <button
                type="button"
                className="bubble-tag-item"
                style={{
                  animationDelay: `${(idx % 4) * 0.3}s`,
                  borderLeftColor: theme.indicatorColor,
                  ["--tag-hover-bg" as string]: theme.badgeBg,
                } as CSSProperties}
                onClick={() => onSelect(finding, idx)}
              >
                {finding.bubbleLabel}
              </button>
            </span>
          );
        })}
      </div>
      <button className="btn-brand-primary btn-summary-margin" type="button" onClick={onShowAll}>
        전체 목록 보기
      </button>
    </div>
  );
}
