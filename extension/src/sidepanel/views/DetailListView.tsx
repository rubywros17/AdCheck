import { useEffect, useId, useRef, useState } from "react";
import type { FilterCategory, FindingWithKeyword } from "../types";

interface Props {
  findings: FindingWithKeyword[];
  activeFilter: FilterCategory;
  expandedFindings: Set<number>;
  pendingScrollIdx: number | null;
  currentPageTitle: string;
  pageUrl: string;
  onBack: () => void;
  onShare: () => void;
  onFilterChange: (filter: FilterCategory) => void;
  onToggleFinding: (idx: number) => void;
  onScrollComplete: () => void;
  onLocateFinding: (finding: FindingWithKeyword) => void;
  onAnalyze: () => void;
  onReset: () => void;
}

export function DetailListView({
  findings, activeFilter, expandedFindings, pendingScrollIdx, currentPageTitle, pageUrl,
  onBack, onShare, onFilterChange, onToggleFinding, onScrollComplete, onLocateFinding,
  onAnalyze, onReset,
}: Props) {
  const [isCategoryInfoOpen, setIsCategoryInfoOpen] = useState(false);
  const viewRef = useRef<HTMLDivElement>(null);
  const categoryInfoRef = useRef<HTMLDivElement>(null);
  const viewId = useId();
  const popoverId = `${viewId}-category-info`;
  const filteredFindings = findings
    .map((finding, idx) => ({ finding, idx }))
    .filter(({ finding }) => {
      if (activeFilter === "DISEASE") return finding.message.includes("의약품");
      if (activeFilter === "GUARANTEE") return finding.message.includes("과장");
      return true;
    });

  useEffect(() => {
    if (!isCategoryInfoOpen) return;
    function handleOutsideClick(event: MouseEvent) {
      if (event.target instanceof Node && !categoryInfoRef.current?.contains(event.target)) {
        setIsCategoryInfoOpen(false);
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setIsCategoryInfoOpen(false);
    }
    document.addEventListener("click", handleOutsideClick);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("click", handleOutsideClick);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [isCategoryInfoOpen]);

  useEffect(() => {
    if (pendingScrollIdx === null) return;
    const timer = window.setTimeout(() => {
      const target = viewRef.current?.querySelector(`[data-finding-idx="${pendingScrollIdx}"]`);
      const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      target?.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth", block: "center" });
      onScrollComplete();
    }, 60);
    return () => window.clearTimeout(timer);
  }, [pendingScrollIdx, activeFilter, onScrollComplete]);

  return (
    <div className="toss-result-stream" ref={viewRef}>
      <div className="detail-page-nav stagger-entry" style={{ animationDelay: "0.04s" }}>
        <div className="unified-step-bar inline-step-bar">
          <button
            type="button"
            className="btn-step-back btn-step-back-summary"
            onClick={onBack}
            aria-label="요약으로 돌아가기"
          >
            ← 요약
          </button>
        </div>
        <div className="detail-nav-right">
          <button type="button" className="btn-nav-share" aria-label="검토 결과 공유하기" onClick={onShare}>
            <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="18" cy="5" r="3" />
              <circle cx="6" cy="12" r="3" />
              <circle cx="18" cy="19" r="3" />
              <line x1="8.59" y1="13.51" x2="15.42" y2="17.49" />
              <line x1="15.41" y1="6.51" x2="8.59" y2="10.49" />
            </svg>
            결과 공유하기
          </button>
        </div>
      </div>

      <div className="category-tabs-row stagger-entry" style={{ animationDelay: "0.06s" }}>
        <div className="category-tabs" role="group" aria-label="검토 문구 유형 필터">
          <button
            type="button"
            className={`category-tab ${activeFilter === "ALL" ? "tab-active" : ""}`}
            aria-pressed={activeFilter === "ALL"}
            onClick={() => onFilterChange("ALL")}
          >
            전체
          </button>
          <button
            type="button"
            className={`category-tab tab-disease ${activeFilter === "DISEASE" ? "tab-active" : ""}`}
            aria-pressed={activeFilter === "DISEASE"}
            onClick={() => onFilterChange("DISEASE")}
          >
            오인 우려 표현
          </button>
          <button
            type="button"
            className={`category-tab tab-guarantee ${activeFilter === "GUARANTEE" ? "tab-active" : ""}`}
            aria-pressed={activeFilter === "GUARANTEE"}
            onClick={() => onFilterChange("GUARANTEE")}
          >
            과장 표현
          </button>
        </div>
        <div className="category-info-wrap" ref={categoryInfoRef}>
          <button
            type="button"
            className="category-info-btn"
            aria-label="용어 설명 보기"
            aria-expanded={isCategoryInfoOpen}
            aria-controls={isCategoryInfoOpen ? popoverId : undefined}
            onClick={() => setIsCategoryInfoOpen((prev) => !prev)}
          >
            i
          </button>
          {isCategoryInfoOpen && (
            <div className="category-info-popover" id={popoverId}>
              <div className="info-popover-item">
                <strong>오인 우려 표현</strong>
                <p>질병의 예방·치료에 효능이 있는 것처럼 오인될 수 있는 표현이에요.</p>
              </div>
              <div className="info-popover-item">
                <strong>과장 표현</strong>
                <p>객관적 근거 없이 효능을 절대적으로 보장하거나 부풀린 표현이에요.</p>
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="page-info-card stagger-entry" style={{ animationDelay: "0.07s" }}>
        <div className="page-info-icon-muted">
          <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10" />
            <line x1="2" y1="12" x2="22" y2="12" />
            <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
          </svg>
        </div>
        <div className="page-info-text-wrap">
          <span className="page-info-label">분석 대상 페이지</span>
          <a href={pageUrl} className="page-info-title" target="_blank" rel="noreferrer">
            {currentPageTitle} 외 상세페이지
          </a>
          <span className="page-info-url">{pageUrl}</span>
        </div>
      </div>

      <p className="chips-guide-text chips-guide-standalone stagger-entry" style={{ animationDelay: "0.10s" }}>
        문구를 클릭하면 펼쳐서 공식 기준과 비교해 볼 수 있어요
      </p>
      <div className="finding-feed stagger-entry" style={{ animationDelay: "0.13s" }}>
        {filteredFindings.length === 0 ? (
          <p className="no-filtered-item">해당 유형의 검토 문구가 없어요.</p>
        ) : (
          filteredFindings.map(({ finding, idx }) => {
            const isDanger = finding.message.includes("의약품");
            const isOpen = expandedFindings.has(idx);
            const contentId = `${viewId}-finding-${idx}`;
            return (
              <div key={idx} className="finding-row" data-finding-idx={idx}>
                <button
                  type="button"
                  className="finding-row-header"
                  onClick={() => onToggleFinding(idx)}
                  aria-expanded={isOpen}
                  aria-controls={isOpen ? contentId : undefined}
                >
                  <span className="finding-row-left">
                    <span className={`cat-chip ${isDanger ? "cat-chip-disease" : "cat-chip-guarantee"}`}>
                      {isDanger ? "오인 우려 표현" : "과장 표현"}
                    </span>
                    <span className="finding-row-title">{finding.keyword}</span>
                  </span>
                  <svg
                    aria-hidden="true"
                    className={`accordion-chevron ${isOpen ? "chevron-open" : ""}`}
                    width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"
                  >
                    <polyline points="6 9 12 15 18 9" />
                  </svg>
                </button>
                {isOpen && (
                  <div id={contentId} className="finding-row-content finding-expand-anim">
                    <div className="finding-detail-box">
                      <div className="content-row-clean">
                        <span className="row-label-clean">광고 본문</span>
                        <p className="row-value-bold">"{finding.sourceText}"</p>
                      </div>
                      <div className="content-row-clean border-top-subtle">
                        <span className="row-label-clean">식약처 공식 기준</span>
                        <p className="row-value-regular">{finding.officialFunction}</p>
                      </div>
                    </div>
                    <button type="button" className="finding-row-link" onClick={() => onLocateFinding(finding)}>
                      상세페이지 위치 확인하기 ›
                    </button>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
      <div className="detail-bottom-actions stagger-entry" style={{ animationDelay: `${0.18 + findings.length * 0.02}s` }}>
        <button className="btn-brand-outline" type="button" onClick={onAnalyze}>
          다시 점검하기
        </button>
        <button className="btn-brand-primary" type="button" onClick={onReset}>
          새로운 광고 점검하기
        </button>
      </div>
    </div>
  );
}
