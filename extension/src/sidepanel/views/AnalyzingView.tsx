import { useEffect, useRef, type CSSProperties } from "react";

interface Props {
  scanCycleMs: number;
}

export function AnalyzingView({ scanCycleMs }: Props) {
  const leftBeamRef = useRef<HTMLSpanElement>(null);
  const rightBeamRef = useRef<HTMLSpanElement>(null);
  const scanTitleRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    let rafId: number;
    const startTime = performance.now();
    const textScanCycleMs = scanCycleMs * 1.18;

    function tick(now: number) {
      const elapsed = now - startTime;
      const cyclePos = elapsed % (scanCycleMs * 2);
      const progress = cyclePos <= scanCycleMs ? cyclePos / scanCycleMs : 2 - cyclePos / scanCycleMs;
      const sweepX = 0.5 - 0.5 * Math.cos(progress * Math.PI);
      const angleDeg = 38 + sweepX * -76;
      const scaleX = 1 + 0.12 * Math.sin(progress * Math.PI);
      const opacity = 0.9 + 0.1 * Math.sin(progress * Math.PI);

      for (const beamRef of [leftBeamRef, rightBeamRef]) {
        if (beamRef.current) {
          beamRef.current.style.transform = `rotate(${angleDeg}deg) scaleX(${scaleX})`;
          beamRef.current.style.opacity = String(opacity);
        }
      }

      const textCyclePos = elapsed % (textScanCycleMs * 2);
      const textProgress = textCyclePos <= textScanCycleMs
        ? textCyclePos / textScanCycleMs
        : 2 - textCyclePos / textScanCycleMs;
      const textSweepX = 0.5 - 0.5 * Math.cos(textProgress * Math.PI);
      const title = scanTitleRef.current;
      if (title) {
        const titleWidth = title.getBoundingClientRect().width;
        title.style.backgroundPosition = `${textSweepX * titleWidth - titleWidth * 1.5}px 0`;
      }

      rafId = requestAnimationFrame(tick);
    }

    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [scanCycleMs]);

  return (
    <div className="toss-loading-box" role="status">
      <div
        className="laser-bear-wrap-clean"
        role="img"
        aria-label="입체 곰돌이 눈에서 빔을 쏘며 스캔 중"
        style={{ "--laser-scan-duration": `${scanCycleMs}ms` } as CSSProperties}
      >
        <span className="laser-bear-shadow" aria-hidden="true" />
        <div className="laser-bear-face" aria-hidden="true">
          <img src="/icons/adcheck_icon.png" alt="" className="laser-bear-logo-img" />
          <span className="laser-eye laser-eye-left">
            <span className="laser-eye-dot" />
            <span className="laser-beam" ref={leftBeamRef} />
          </span>
          <span className="laser-eye laser-eye-right">
            <span className="laser-eye-dot" />
            <span className="laser-beam" ref={rightBeamRef} />
          </span>
        </div>
      </div>
      <h3 className="loading-title loading-title-scan" ref={scanTitleRef}>
        광고 문구를 꼼꼼히 스캔 중이에요
      </h3>
      <p className="loading-sub">식약처 공식 고시 기준과 대조하고 있어요</p>
    </div>
  );
}
