import { useEffect, useRef } from "react";
import type { ScanHistoryItem } from "../types";
import { REVIEW_LEVEL_LABEL } from "../constants/reviewLevelLabels";

interface Props {
  histories: ScanHistoryItem[];
  onClose: () => void;
  onSelect: (history: ScanHistoryItem) => void;
  onToggleFavorite: (id: string) => void;
}

function getBadge(history: ScanHistoryItem) {
  const state = history.level === "SAFE" ? "safe" : history.level === "CAUTION" ? "warning" : "review";
  const label = REVIEW_LEVEL_LABEL[history.level];
  const text = history.level === "SAFE" ? label : `${label} ${history.count}건`;
  return { state, text };
}

export function HistoryModal({ histories, onClose, onSelect, onToggleFavorite }: Props) {
  const sheetRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const previouslyFocused = document.activeElement;
    closeRef.current?.focus();
    return () => {
      if (previouslyFocused instanceof HTMLElement && previouslyFocused.isConnected) {
        previouslyFocused.focus();
      }
    };
  }, []);

  // 즐겨찾기(별표) 켠 항목을 위로 고정. Array.sort는 stable이라 그 안에서의 원래 순서(최신순)는 유지됩니다.
  const sortedHistories = [...histories].sort((a, b) => (b.favorite ? 1 : 0) - (a.favorite ? 1 : 0));

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        ref={sheetRef}
        className="modal-bottom-sheet history-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="history-modal-title"
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.stopPropagation();
            onClose();
          }
          if (event.key !== "Tab") return;
          const buttons = sheetRef.current?.querySelectorAll<HTMLButtonElement>("button");
          if (!buttons?.length) return;
          const first = buttons[0];
          const last = buttons[buttons.length - 1];
          if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
          } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
          }
        }}
      >
        <div className="modal-drag-bar" aria-hidden="true" />
        <div className="modal-head">
          <h2 id="history-modal-title" className="history-modal-title">
            최근 점검 기록
          </h2>
          <button ref={closeRef} type="button" className="btn-modal-close" onClick={onClose} aria-label="점검 기록 닫기">
            ✕
          </button>
        </div>

        {histories.length === 0 ? (
          <p className="no-filtered-item">저장된 점검 기록이 없어요.</p>
        ) : (
          <div className="history-list-stack">
            {sortedHistories.map((history) => {
              const badge = getBadge(history);
              return (
                <div key={history.id} className="history-item-card">
                  <button
                    type="button"
                    className={`history-star-btn ${history.favorite ? "active" : ""}`}
                    onClick={(event) => {
                      event.stopPropagation();
                      onToggleFavorite(history.id);
                    }}
                    aria-label={history.favorite ? "즐겨찾기 해제" : "즐겨찾기 추가"}
                    aria-pressed={!!history.favorite}
                  >
                    {history.favorite ? "★" : "☆"}
                  </button>
                  <button type="button" className="history-item-select" onClick={() => onSelect(history)}>
                    <span className="history-item-info">
                      <span className="history-date">{history.dateStr}</span>
                      <strong className="history-prod-name">{history.productName}</strong>
                    </span>
                    <span className={`history-status-badge ${badge.state}`}>{badge.text}</span>
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
