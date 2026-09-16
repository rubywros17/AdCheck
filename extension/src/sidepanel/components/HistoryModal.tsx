import { useEffect, useRef } from "react";
import type { ScanHistoryItem } from "../types";

interface Props {
  histories: ScanHistoryItem[];
  onClose: () => void;
  onSelect: (history: ScanHistoryItem) => void;
}

export function HistoryModal({ histories, onClose, onSelect }: Props) {
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
          <h2 id="history-modal-title" className="history-modal-title">최근 점검 기록</h2>
          <button ref={closeRef} type="button" className="btn-modal-close" onClick={onClose} aria-label="점검 기록 닫기">
            ✕
          </button>
        </div>
        <div className="history-list-stack">
          {histories.length === 0 ? (
            <p className="no-filtered-item">저장된 점검 기록이 없어요.</p>
          ) : (
            histories.map((history) => {
              const badgeState = history.level === "SAFE" ? "safe" : history.level === "CAUTION" ? "warning" : "review";
              const badgeText = history.level === "SAFE" ? "안심" : history.level === "CAUTION" ? `검토 ${history.count}건` : `주의 ${history.count}건`;

              return (
                <button key={history.id} type="button" className="history-item-card" onClick={() => onSelect(history)}>
                  <span className="history-item-info">
                    <span className="history-date">{history.dateStr}</span>
                    <strong className="history-prod-name">{history.productName}</strong>
                  </span>
                  <span className={`history-status-badge ${badgeState}`}>{badgeText}</span>
                </button>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
