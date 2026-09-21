import React, { useEffect, useState } from "react";
import { ShieldAnimation } from "../components/animations/ShieldAnimation";
import { CertMarkAnimation } from "../components/animations/CertMarkAnimation";
import { WarningAnimation } from "../components/animations/WarningAnimation";
import { PillAnimation } from "../components/animations/PillAnimation";
import { ReviewAnimation } from "../components/animations/ReviewAnimation";
import { AirplaneAnimation } from "../components/animations/AirplaneAnimation";

type TipTheme = "shield" | "warning" | "pill" | "review" | "airplane";

interface TipItem {
  theme: TipTheme;
  text: string;
  // 실제 인증마크 이미지를 보여줘야 하는 팁(예: "인증마크로 확인하세요")에만 표시
  certMark?: boolean;
}

const GRAPHIC_BY_THEME: Record<TipTheme, React.ComponentType> = {
  shield: ShieldAnimation,
  warning: WarningAnimation,
  pill: PillAnimation,
  review: ReviewAnimation,
  airplane: AirplaneAnimation,
};

// 인증마크를 직접 언급하는 팁은 실제 인증마크 이미지로, 나머지는 테마 기본 그래픽으로
function getGraphicForTip(tip: TipItem): React.ComponentType {
  if (tip.certMark) return CertMarkAnimation;
  return GRAPHIC_BY_THEME[tip.theme];
}

// 카드 1장이 화면에 머무는 시간
const CARD_DISPLAY_MS = 6000;
// 카드가 옆으로 밀려나며 전환되는 데 걸리는 시간
const SLIDE_MS = 500;

// 17가지 식약처 공인 부당광고 상식 문장
const ADCHECK_TIPS: TipItem[] = [
  // 1. 방패 / 인증마크 테마 (shield)
  { theme: "shield", text: "건강기능식품은\n패키지 인증마크로 확인할 수 있어요.", certMark: true },
  { theme: "shield", text: "인정받은 제품인지\n'식품안전나라'에서 검색해보세요." },
  { theme: "shield", text: "'기능성 표시식품'은\n건강기능식품과 달라요." },

  // 2. 해외직구 비행기 테마 (airplane)
  { theme: "airplane", text: "해외직구 영양제는\n식약처 인증 건강기능식품이 아니에요." },
  { theme: "airplane", text: "해외직구 식품과 일반식품은\n건강기능식품이 아니에요." },

  // 3. 경고 도장 테마 (warning)
  { theme: "warning", text: "일반식품은 '피로회복' 문구를 쓸 수 없어요." },
  { theme: "warning", text: "일반식품에 '혈당조절' 문구를 쓸 수 없어요." },
  { theme: "warning", text: "일반식품에 '항산화' 등의 문구를 쓰면\n건강기능식품 오인 광고예요." },
  { theme: "warning", text: "원재료 효능 논문을\n제품 효능처럼 광고할 수 없어요." },
  { theme: "warning", text: "'부작용 없음', '100% 천연' 같은\n절대적 표현은 금지돼요." },
  { theme: "warning", text: "호박즙, 효소 등 일반식품은\n붓기 제거 광고를 할 수 없어요." },

  // 4. 알약 / 의약품 오인 테마 (pill)
  { theme: "pill", text: "건강기능식품은\n질병을 치료하는 의약품이 아니에요." },
  { theme: "pill", text: "건강기능식품은 질병을 예방하는 의약품처럼\n광고할 수 없어요." },
  { theme: "pill", text: "멜라토닌 함유 식품은\n불면증 치료 효과가 없어요." },
  { theme: "pill", text: "국내에 탈모 치료 효과를 인정받은 건강기능식품은 없어요." },
  { theme: "pill", text: "'키 크는 영양제', '수험생 총명환'은\n인정된 기능성이 아니에요." },

  // 5. 구매후기 / 체험기 테마 (review)
  { theme: "review", text: "'먹고 완치됐다'는\n체험기·구매후기 광고는 불법이에요." },
];

// 같은 테마를 가진 팁이 서로 이웃하는지 검사
function hasAdjacentSameTheme(tips: TipItem[]): boolean {
  return tips.some((tip, i) => i > 0 && tip.theme === tips[i - 1].theme);
}

// 피셔-예이츠 셔플: 매 바퀴마다 새로 섞어서 같은 순서가 반복되지 않게 함.
// 바로 옆 카드끼리 같은 테마가 연속되지 않을 때까지 다시 섞고(가능하면 이전 바퀴 마지막 카드와도 겹치지 않게),
// 최대 200번 시도해도 못 찾으면 마지막으로 나온 결과를 그대로 사용.
function shuffleTips(avoidLeadingTheme?: TipTheme): TipItem[] {
  let candidate: TipItem[] = ADCHECK_TIPS;
  for (let attempt = 0; attempt < 200; attempt++) {
    candidate = [...ADCHECK_TIPS];
    for (let i = candidate.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [candidate[i], candidate[j]] = [candidate[j], candidate[i]];
    }
    if (hasAdjacentSameTheme(candidate)) continue;
    if (avoidLeadingTheme !== undefined && candidate[0].theme === avoidLeadingTheme) continue;
    break;
  }
  return candidate;
}

function CardContent({ tip }: { tip: TipItem }) {
  const Graphic = getGraphicForTip(tip);
  return (
    <>
      {/* 상단 쫀득 동적 그래픽 (팁 테마와 매칭) */}
      <div className="analyzing-graphic-wrap">
        <Graphic />
      </div>
      {/* 한 줄 헤드라인: 일러스트와 세트로 함께 슬라이드 */}
      <h2 className="analyzing-main-headline">{tip.text}</h2>
    </>
  );
}

export function AnalyzingView() {
  // 셔플된 17개 팁 전체를 순환. 한 바퀴 다 돌면 다시 셔플해서 이어감 —
  // 실제 광고 검수가 끝날 때까지(부모가 이 컴포넌트를 내릴 때까지) 계속 순환할 수 있도록,
  // "몇 장 보여주고 끝" 같은 고정 총 노출 시간을 이 컴포넌트가 스스로 정하지 않음.
  const [shuffledTips, setShuffledTips] = useState<TipItem[]>(shuffleTips);
  // 현재 바퀴가 끝나는 순간(마지막 카드 -> 첫 카드) 슬라이드 미리보기가 실제로 이어질 카드와
  // 어긋나지 않도록, 다음 바퀴 분량을 미리 한 바퀴 앞서 셔플해둠(이전 바퀴 마지막 테마와도 안 겹치게).
  const [nextLapTips, setNextLapTips] = useState<TipItem[]>(() =>
    shuffleTips(shuffledTips[shuffledTips.length - 1].theme)
  );

  // 현재 화면에 정착해 있는 카드의 shuffledTips 기준 인덱스
  const [activeIndex, setActiveIndex] = useState(0);
  // 슬라이드 전환이 진행 중인 0.5초 구간에만 true
  const [isSliding, setIsSliding] = useState(false);

  // 카드 1장 → (5초 후) 슬라이드 시작 → (0.5초 후) 다음 카드로 정착.
  // 끝에 도달하면 미리 준비해둔 다음 바퀴로 넘어가고, 그다음 바퀴를 새로 셔플해둠 — 무한 순환.
  useEffect(() => {
    const startSlide = window.setTimeout(() => setIsSliding(true), CARD_DISPLAY_MS);
    const settleNext = window.setTimeout(() => {
      setActiveIndex((prev) => {
        const next = prev + 1;
        if (next >= shuffledTips.length) {
          setShuffledTips(nextLapTips);
          setNextLapTips(shuffleTips(nextLapTips[nextLapTips.length - 1].theme));
          return 0;
        }
        return next;
      });
      setIsSliding(false);
    }, CARD_DISPLAY_MS + SLIDE_MS);

    return () => {
      window.clearTimeout(startSlide);
      window.clearTimeout(settleNext);
    };
  }, [activeIndex, shuffledTips, nextLapTips]);

  const nextTip = activeIndex + 1 < shuffledTips.length ? shuffledTips[activeIndex + 1] : nextLapTips[0];

  return (
    <div className="analyzing-tip-card">
      {isSliding ? (
        <>
          {/* 현재 카드: 왼쪽으로 스르륵 퇴장 */}
          <div className="analyzing-card-slot card-slide-exit">
            <CardContent tip={shuffledTips[activeIndex]} />
          </div>
          {/* 다음 카드: 오른쪽에서 부드럽게 등장 */}
          <div className="analyzing-card-slot card-slide-enter">
            <CardContent tip={nextTip} />
          </div>
        </>
      ) : (
        <div className="analyzing-card-slot">
          <CardContent tip={shuffledTips[activeIndex]} />
        </div>
      )}
    </div>
  );
}
