interface Props {
  showHistory: boolean;
  onHome: () => void;
  onOpenHistory: () => void;
}

export function SidepanelHeader({ showHistory, onHome, onOpenHistory }: Props) {
  return (
    <header className="toss-header">
      <button type="button" className="brand-group" onClick={onHome} aria-label="AdCheck 처음으로">
        <span className="glass-shimmer-title">
          <span className="glass-brand-ad">Ad</span>
          <span className="glass-brand-check">Check</span>
        </span>
      </button>
      {showHistory && (
        <button type="button" className="btn-history-trigger" onClick={onOpenHistory} aria-label="점검 기록 열기">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 6 12 12 16 14" />
          </svg>
        </button>
      )}
    </header>
  );
}
