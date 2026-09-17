import type { TestTarget } from "../types";

interface Props {
  testTarget: TestTarget;
  onTestTargetChange: (target: TestTarget) => void;
  // 결과 화면처럼 화면 안에 이미 같은 고지 문구가 있을 때만 true로 넘겨 중복 표시를 피함.
  // 테스트 스위처는 이 값과 무관하게 항상 보여줌.
  hideDisclaimer?: boolean;
}

const TEST_TARGETS: { value: TestTarget; label: string }[] = [
  { value: "NORMAL", label: "주의(10건)" },
  { value: "SAFE", label: "안심(0건)" },
  { value: "INVALID", label: "분석불가" },
];

export function SidepanelFooter({ testTarget, onTestTargetChange, hideDisclaimer }: Props) {
  return (
    <footer className="toss-footer-area">
      {!hideDisclaimer && (
        <p className="toss-footer-text">식약처 고시 기준 기반 안내이며 법적 효력을 갖는 행정처분 결과가 아닙니다.</p>
      )}
      <div className="test-switcher">
        <span className="switcher-lbl">테스트:</span>
        {TEST_TARGETS.map(({ value, label }) => (
          <button
            key={value}
            type="button"
            className={testTarget === value ? "sw-btn active" : "sw-btn"}
            aria-pressed={testTarget === value}
            onClick={() => onTestTargetChange(value)}
          >
            {label}
          </button>
        ))}
      </div>
    </footer>
  );
}
