import { useEffect } from "react";

interface Props {
  onComplete: () => void;
}

export function SplashView({ onComplete }: Props) {
  useEffect(() => {
    const timer = window.setTimeout(onComplete, 1300);
    return () => window.clearTimeout(timer);
  }, [onComplete]);

  return (
    <div className="splash-overlay">
      <div className="splash-content">
        <div className="splash-logo-circle">
          <span className="splash-logo-glow" aria-hidden="true" />
          <span className="splash-logo-ripple" aria-hidden="true" />
          <img
            src="/icons/adcheck_icon.png"
            alt="AdCheck 로고"
            width="72"
            height="72"
            className="splash-logo-img"
          />
        </div>
        <div className="glass-shimmer-title splash-title-large">
          <span className="glass-brand-ad">Ad</span>
          <span className="glass-brand-check">Check</span>
        </div>
      </div>
    </div>
  );
}
