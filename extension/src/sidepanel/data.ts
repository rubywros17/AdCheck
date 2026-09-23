import type { FindingWithKeyword, ScanHistoryItem } from "./types";
import type { FindingSource } from "../types/analysis";

// 근거 링크 렌더링(parseSourceUrls) 테스트용 목업 소스. 나머지 규칙(rule)들은 sources를 아예 안 넣어서
// rule.sources?.length 방어 코드(옵셔널 체이닝) 경로도 함께 테스트한다.
const MOCK_SOURCE_SINGLE: FindingSource = {
  title: "식품 등의 표시·광고에 관한 법률 제8조",
  section: "제8조제1항제1호",
  sourceUrl: "https://law.go.kr/법령/식품등의표시광고에관한법률/제8조 (부당한 표시·광고행위의 금지)",
};
// sourceUrl 하나에 세미콜론으로 URL 2개가 이어붙은 케이스(문서의 LAW-02 사례) 재현
const MOCK_SOURCE_MULTI: FindingSource = {
  title: "식품 등의 표시·광고에 관한 법률 시행령 제3조",
  section: "제3조",
  sourceUrl:
    "https://law.go.kr/법령/식품등의표시광고에관한법률시행령/제3조 (질병 예방·치료 표시광고); https://law.go.kr/법령/식품등의표시광고에관한법률시행령/제3조/별표 (세부 판단기준)",
};
// sourceUrl이 없는 케이스: <a> 링크 대신 <span> 안내 텍스트로만 표시되는 경로
const MOCK_SOURCE_NO_URL: FindingSource = {
  title: "표시광고법 제3조",
  section: "제3조제1항제2호",
  sourceUrl: null,
};

// Existing demo fixtures used by the sidepanel test controls.
// AnalyzingView는 더 이상 스스로 완료를 판단하지 않고 팁 카드를 셔플하며 무한 순환하므로,
// "분석(목업)이 몇 초간 진행되는가"는 오직 이 값으로만 제어됩니다.
export const SCAN_CYCLE_MS = 6000; // 6초
export const SCAN_HISTORY_STORAGE_KEY = "adcheck_scan_histories";
export const CURRENT_PAGE_TITLE = "프리미엄 눈 건강 루테인 지아잔틴 1000mg";

export const CURRENT_PAGE_URL = "https://example.com/product/12345";

export const DEFAULT_SCAN_HISTORIES: ScanHistoryItem[] = [
  {
    id: "h1",
    dateStr: "오늘 오후 10:20",
    productName: CURRENT_PAGE_TITLE,
    pageUrl: CURRENT_PAGE_URL,
    count: 10,
    level: "REVIEW",
  },
  {
    id: "h2",
    dateStr: "오늘 오후 6:05",
    productName: "초고함량 식물성 알티지 오메가3",
    pageUrl: "https://example.com/product/23456",
    count: 0,
    level: "SAFE",
  },
  {
    id: "h3",
    dateStr: "오늘 오후 3:15",
    productName: "관절엔 초록잎홍합 & MSM 콤플렉스",
    pageUrl: "https://example.com/product/34567",
    count: 2,
    level: "CAUTION",
  },
  {
    id: "h4",
    dateStr: "오늘 오전 11:40",
    productName: "간 건강 밀크씨슬 실리마린 800mg",
    pageUrl: "https://example.com/product/45678",
    count: 7,
    level: "REVIEW",
  },
  {
    id: "h5",
    dateStr: "오늘 오전 9:05",
    productName: "체지방 감소 가르시니아 컴플렉스",
    pageUrl: "https://example.com/product/56789",
    count: 1,
    level: "CAUTION",
  },
];

export const MOCK_FINDINGS: FindingWithKeyword[] = [
  {
    keyword: "노안·백내장 근본 예방 및 시력 100% 완벽 회복 보장 특급 솔루션",
    bubbleLabel: "노안·백내장 예방",
    sourceText: "본 영양제는 단 2주일 만에 노안과 백내장을 근본적으로 예방하고 시력을 100% 완벽히 회복시켜 드립니다.",
    selector: "p.claim-1",
    // 하나의 문장에 여러 규칙이 매핑되는 케이스 테스트: 대표 규칙(의약품 오인) 외에 "100% 강조" 규칙도 함께 판정됨
    rules: [
      {
        message: "의약품 오인",
        riskLevel: "HIGH",
        category: "VISION",
        officialFunction: "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음",
        sources: [MOCK_SOURCE_SINGLE],
      },
      {
        message: "100% 표현 과장",
        riskLevel: "CAUTION",
        category: "INGREDIENT_100",
        officialFunction: "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음",
      },
    ],
  },
  {
    keyword: "손상된 간세포 즉각 재생",
    bubbleLabel: "간세포 즉각 재생",
    sourceText: "잦은 음주로 극심하게 파괴된 간세포를 혁신적으로 즉각 재생시켜 줍니다.",
    selector: "p.claim-2",
    rules: [
      {
        message: "의약품 오인",
        riskLevel: "HIGH",
        category: "REGEN_CANCER",
        officialFunction: "간 건강에 도움을 줄 수 있음",
      },
    ],
  },
  {
    keyword: "만성 관절염 완치",
    bubbleLabel: "관절염 완치",
    sourceText: "시큰거리는 퇴행성 관절염 통증을 며칠 만에 깨끗하게 완치 보장합니다.",
    selector: "p.claim-3",
    rules: [
      {
        message: "의약품 오인",
        riskLevel: "HIGH",
        category: "PAIN",
        officialFunction: "관절 및 연골건강에 도움을 줄 수 있음",
        sources: [MOCK_SOURCE_MULTI, MOCK_SOURCE_NO_URL],
      },
    ],
  },
  {
    keyword: "혈관 핏떡 100% 융해",
    bubbleLabel: "혈전 100% 융해",
    sourceText: "혈액 속 뭉친 혈전과 핏떡을 100% 녹여내어 뇌졸중을 막아줍니다.",
    selector: "p.claim-4",
    rules: [
      {
        message: "의약품 오인",
        riskLevel: "HIGH",
        category: "VESSEL",
        officialFunction: "혈중 중성지질 개선·혈행개선에 도움을 줄 수 있음",
      },
    ],
  },
  {
    keyword: "체지방 100% 완전 분해",
    bubbleLabel: "체지방 100% 완전 분해",
    sourceText: "운동이나 식단 조절 전혀 없이도 섭취된 탄수화물과 체지방을 100% 태웁니다.",
    selector: "p.claim-5",
    // 규칙 3개가 매핑되는 케이스 테스트: "그 외 판정된 규칙 (2건)"으로 노출됨.
    // rules[0]/[1]에는 sources를 채워 대표 근거 법령 1줄 + 배지 팝오버 내 서브 규칙 미니 링크(↗)를 함께 테스트하고,
    // rules[2]는 sources를 비워둬 링크 없는 방어 경로(라벨만 노출)도 함께 확인한다.
    rules: [
      {
        message: "과장 광고",
        riskLevel: "HIGH",
        category: "WEIGHT_FAT",
        officialFunction: "탄수화물이 지방으로 합성되는 것을 억제하여 체지방 감소에 도움을 줄 수 있음",
        sources: [
          {
            title: "식품 등의 표시·광고에 관한 법률",
            section: "제8조제1항제1호",
            sourceUrl: "https://law.go.kr/법령/식품등의표시광고에관한법률/제8조 (부당한 표시·광고행위의 금지)",
          },
        ],
      },
      {
        message: "쉽고 빠른 감량 표방",
        riskLevel: "HIGH",
        category: "EASY_DIET",
        officialFunction: "탄수화물이 지방으로 합성되는 것을 억제하여 체지방 감소에 도움을 줄 수 있음",
        sources: [
          {
            title: "식품 등의 표시·광고에 관한 법률",
            section: "제8조제1항제2호",
            sourceUrl: "https://law.go.kr/법령/식품등의표시광고에관한법률/제8조 (사실과 다르거나 과장된 표시·광고행위의 금지)",
          },
        ],
      },
      {
        message: "식욕 억제 과장",
        riskLevel: "CAUTION",
        category: "SATIETY_COFFEE",
        officialFunction: "탄수화물이 지방으로 합성되는 것을 억제하여 체지방 감소에 도움을 줄 수 있음",
      },
    ],
  },
  {
    keyword: "기적의 활력 부스터",
    bubbleLabel: "피로 즉각 해소",
    sourceText: "먹자마자 3초 만에 만성 피로가 즉각 날아가는 기적의 에너지 폭탄",
    selector: "p.claim-6",
    rules: [
      {
        message: "과장 광고",
        riskLevel: "CAUTION",
        category: "FATIGUE",
        officialFunction: "피로개선에 도움을 줄 수 있음",
      },
    ],
  },
  {
    keyword: "단 3일 7kg 감량 보장",
    bubbleLabel: "3일 7kg 감량 보장",
    sourceText: "임상 증명 완료! 3일간 섭취하면 무조건 체중 7kg 감량을 보장해 드립니다.",
    selector: "p.claim-7",
    rules: [
      {
        message: "과장 광고",
        riskLevel: "HIGH",
        category: "RESULT_TIME_AMOUNT",
        officialFunction: "체지방 감소에 도움을 줄 수 있음",
      },
    ],
  },
  {
    keyword: "일일 권장량 1000% 배합",
    bubbleLabel: "고단위 1000% 배합",
    sourceText: "시중 제품과는 차원이 다른 슈퍼 고단위 압축 배합으로 효과가 10배 뛰어납니다.",
    selector: "p.claim-8",
    rules: [
      {
        message: "과장 광고",
        riskLevel: "CAUTION",
        category: "CONCENTRATION",
        officialFunction: "영양소 보충 및 건강 증진에 도움을 줄 수 있음",
      },
    ],
  },
  {
    keyword: "전문의 만장일치 보증",
    bubbleLabel: "전문의 효과 보증",
    sourceText: "대한민국 최고 권위 전문의들이 직접 효과를 보증하고 만장일치로 추천한 제품",
    selector: "p.claim-9",
    rules: [
      {
        message: "과장 광고",
        riskLevel: "HIGH",
        category: "EXPERT_ENDORSEMENT",
        officialFunction: "건강기능식품 공통 기준",
      },
    ],
  },
  {
    keyword: "초고속 면역력 급상승",
    bubbleLabel: "면역력 급상승",
    sourceText: "감기 바이러스를 단숨에 사멸시키는 최강의 면역 코팅제",
    selector: "p.claim-10",
    rules: [
      {
        message: "의약품 오인",
        riskLevel: "HIGH",
        category: "COLD",
        officialFunction: "면역기능 유지에 도움을 줄 수 있음",
      },
    ],
  },
];

