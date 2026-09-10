import { useEffect, useState } from "react";
import type { ReviewLevel } from "../types";

interface Props {
  count: number;
  level: ReviewLevel;
}

const ROTATION_ANGLES: Record<ReviewLevel, number> = { SAFE: 0, CAUTION: 85, REVIEW: 160 };
const STATUS_COLORS: Record<ReviewLevel, string> = { SAFE: "#10B981", CAUTION: "#F59E0B", REVIEW: "#EF4444" };

export function ReferenceArcGauge({ count, level }: Props) {
  const [knobRotateAngle, setKnobRotateAngle] = useState(0);
  const [animatedCount, setAnimatedCount] = useState(0);
  const targetAngle = ROTATION_ANGLES[level];
  const activeColor = STATUS_COLORS[level];

  useEffect(() => {
    setKnobRotateAngle(0);
    const timer = window.setTimeout(() => setKnobRotateAngle(targetAngle), 120);
    return () => window.clearTimeout(timer);
  }, [targetAngle]);

  useEffect(() => {
    setAnimatedCount(0);
    let frameId = 0;
    const duration = 750;
    const startTime = performance.now();
    const updateAnimation = (currentTime: number) => {
      const progress = Math.min(1, (currentTime - startTime) / duration);
      const easeOut = 1 - Math.pow(1 - progress, 4);
      setAnimatedCount(Math.round(easeOut * count));
      if (progress < 1) frameId = requestAnimationFrame(updateAnimation);
    };
    const timer = window.setTimeout(() => {
      frameId = requestAnimationFrame(updateAnimation);
    }, 100);
    return () => {
      window.clearTimeout(timer);
      cancelAnimationFrame(frameId);
    };
  }, [count]);

  return (
    <div className="reference-gauge-wrap" role="img" aria-label={`검토 필요 ${count}건`}>
      <svg viewBox="0 0 260 145" className="ref-gauge-svg" aria-hidden="true">
        <defs>
          <linearGradient id="pureGreenToRedGrad" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="#10B981" />
            <stop offset="35%" stopColor="#FBBF24" />
            <stop offset="70%" stopColor="#F97316" />
            <stop offset="100%" stopColor="#EF4444" />
          </linearGradient>
          <linearGradient id="arcGlassShimmer" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.8" />
            <stop offset="40%" stopColor="#FFFFFF" stopOpacity="0.15" />
            <stop offset="100%" stopColor="#000000" stopOpacity="0.06" />
          </linearGradient>
          <filter id="glassArcShadow" x="-10%" y="-10%" width="120%" height="130%">
            <feDropShadow dx="0" dy="3" stdDeviation="4" floodColor="#0F172A" floodOpacity="0.08" />
          </filter>
          <filter id="knobGlow" x="-30%" y="-30%" width="160%" height="160%">
            <feDropShadow dx="0" dy="2" stdDeviation="3" floodColor="#0F172A" floodOpacity="0.16" />
          </filter>
        </defs>
        <path d="M 35 118 A 95 95 0 0 1 225 118" fill="none" stroke="#F1F5F9" strokeWidth="14" strokeLinecap="round" />
        <path d="M 35 118 A 95 95 0 0 1 225 118" fill="none" stroke="url(#pureGreenToRedGrad)" strokeWidth="14" strokeLinecap="round" filter="url(#glassArcShadow)" />
        <path className="ref-gauge-shimmer" d="M 35 118 A 95 95 0 0 1 225 118" fill="none" stroke="url(#arcGlassShimmer)" strokeWidth="14" strokeLinecap="round" />
        <g className="ref-gauge-knob" style={{ transform: `rotate(${knobRotateAngle}deg)` }}>
          <circle cx="35" cy="118" r="9.5" fill="#FFFFFF" stroke={activeColor} strokeWidth="3.5" filter="url(#knobGlow)" />
          <circle cx="35" cy="118" r="3.5" fill="#FFFFFF" />
        </g>
      </svg>
      <div className="ref-gauge-inner-center" aria-hidden="true">
        <span className="ref-count-guide">검토 필요</span>
        <span className="ref-count-num" style={{ color: activeColor }}>{animatedCount}</span>
      </div>
      <div className="ref-bottom-labels-3stage" aria-hidden="true">
        <span className="lbl-step-item lbl-step-left">안심</span>
        <span className="lbl-step-item lbl-step-right">검토</span>
      </div>
    </div>
  );
}
