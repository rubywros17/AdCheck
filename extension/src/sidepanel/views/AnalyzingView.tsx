import React, { useEffect, useState } from "react";
import { ShieldAnimation } from "../components/animations/ShieldAnimation";
import { CertMarkAnimation } from "../components/animations/CertMarkAnimation";
import { WarningAnimation } from "../components/animations/WarningAnimation";
import { PillAnimation } from "../components/animations/PillAnimation";
import { ReviewAnimation } from "../components/animations/ReviewAnimation";
import { AirplaneAnimation } from "../components/animations/AirplaneAnimation";

interface Props {
  onComplete: () => void;
}

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

// 카드 1장이 화면에 머무는 최소 시간
const CARD_DISPLAY_MS = 3000;
// 카드가 옆으로 밀려나며 전환되는 데 걸리는 시간
const SLIDE_MS = 500;
// 전체 로딩 최소 노출 시간 (카드1 3초 + 전환 0.5초 + 카드2 3초 = 6.5초)
const TOTAL_LOADING_MS = CARD_DISPLAY_MS + SLIDE_MS + CARD_DISPLAY_MS;

// 17가지 식약처 공인 부당광고 상식 문장
const ADCHECK_TIPS: TipItem[] = [
  // 1. 방패 / 인증마크 테마 (shield)
  { theme: "shield", text: "건강기능식품은 패키지 인증마크로 확인할 수 있어요.", certMark: true },
  { theme: "shield", text: "인정받은 제품인지 '식품안전나라'에서 검색해보세요." },
  { theme: "shield", text: "'기능성 표시식품'은 건강기능식품과 달라요." },

  // 2. 해외직구 비행기 테마 (airplane)
  { theme: "airplane", text: "해외직구 영양제는\n식약처 인증 건강기능식품이 아니에요." },
  { theme: "airplane", text: "해외직구 식품과 일반식품은\n건강기능식품이 아니에요." },

  // 3. 경고 도장 테마 (warning)
  { theme: "warning", text: "일반식품은 '피로회복', '혈당조절' 문구를 쓸 수 없어요." },
  { theme: "warning", text: "일반식품에 '항산화' 등의 문구를 쓰면\n건강기능식품 오인 광고예요." },
  { theme: "warning", text: "'혈관을 탄력 있고 부드럽게'는 허위 광고예요." },
  { theme: "warning", text: "원재료 효능 논문을\n제품 효능처럼 광고할 수 없어요." },
  { theme: "warning", text: "'부작용 0%', '100% 천연' 같은 절대적 표현은 금지돼요." },
  { theme: "warning", text: "호박즙, 효소 등 일반식품은\n붓기 제거 광고를 할 수 없어요." },

  // 4. 알약 / 의약품 오인 테마 (pill)
  { theme: "pill", text: "건강기능식품은 질병을 치료하는 의약품이 아니에요." },
  { theme: "pill", text: "건강기능식품은 질병을 예방하는 의약품처럼 광고할 수 없어요." },
  { theme: "pill", text: "멜라토닌 함유 식품은 불면증 치료 효과가 없어요." },
  { theme: "pill", text: "국내에 탈모 치료 효과를 인정받은 건강기능식품은 없어요." },
  { theme: "pill", text: "'키 크는 영양제', '수험생 총명환'은 인정된 기능성이 아니에요." },

  // 5. 구매후기 / 체험기 테마 (review)
  { theme: "review", text: "'먹고 완치됐다'는 체험기·구매후기 광고는 불법이에요." },
];

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

// 진입 시 서로 다른 테마의 팁 2개를 무조건 보장하는 선택 함수
function pickTwoDistinctTips(): [TipItem, TipItem] {
  // 1. 첫 번째 팁 랜덤 선택
  const firstIdx = Math.floor(Math.random() * ADCHECK_TIPS.length);
  const firstTip = ADCHECK_TIPS[firstIdx];

  // 2. 첫 번째 팁과 테마(theme)가 다른 팁들만 필터링
  const differentThemeTips = ADCHECK_TIPS.filter(
    (tip) => tip.theme !== firstTip.theme,
  );

  // 3. 필터링된 목록에서 두 번째 팁 선택 (무조건 다른 테마 보장)
  const secondIdx = Math.floor(Math.random() * differentThemeTips.length);
  const secondTip = differentThemeTips[secondIdx];

  return [firstTip, secondTip];
}

export function AnalyzingView({ onComplete }: Props) {
  // 마운트 시 서로 다른 테마의 팁 2개를 미리 확정 (1번째 <-> 2번째 이미지 테마 겹침 방지)
  const [tips] = useState<[TipItem, TipItem]>(pickTwoDistinctTips);

  // 현재 화면에 정착해 있는 카드 (0 = 첫 번째, 1 = 두 번째)
  const [activeCard, setActiveCard] = useState<0 | 1>(0);
  // 슬라이드 전환이 진행 중인 0.5초 구간에만 true
  const [isSliding, setIsSliding] = useState(false);

  // 카드1 → (3초 후) 슬라이드 시작 → (0.5초 후) 카드2로 정착
  useEffect(() => {
    const startSlide = window.setTimeout(() => setIsSliding(true), CARD_DISPLAY_MS);
    const settleOnCard2 = window.setTimeout(() => {
      setActiveCard(1);
      setIsSliding(false);
    }, CARD_DISPLAY_MS + SLIDE_MS);

    return () => {
      window.clearTimeout(startSlide);
      window.clearTimeout(settleOnCard2);
    };
  }, []);

  // 최소 6.5초(카드1 3초 + 전환 0.5초 + 카드2 3초) 노출을 보장한 뒤 완료 처리
  useEffect(() => {
    const timer = window.setTimeout(onComplete, TOTAL_LOADING_MS);
    return () => window.clearTimeout(timer);
  }, [onComplete]);

  return (
    <div className="analyzing-tip-card">
      {isSliding ? (
        <>
          {/* 현재 카드: 왼쪽으로 스르륵 퇴장 */}
          <div className="analyzing-card-slot card-slide-exit">
            <CardContent tip={tips[activeCard]} />
          </div>
          {/* 다음 카드: 오른쪽에서 부드럽게 등장 */}
          <div className="analyzing-card-slot card-slide-enter">
            <CardContent tip={tips[activeCard === 0 ? 1 : 0]} />
          </div>
        </>
      ) : (
        <div className="analyzing-card-slot">
          <CardContent tip={tips[activeCard]} />
        </div>
      )}
    </div>
  );
}
