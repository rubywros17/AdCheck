import type { FindingWithKeyword, ScanHistoryItem } from "./types";

// Existing demo fixtures used by the sidepanel test controls.
export const SCAN_CYCLE_MS = 2400;
export const SCAN_HISTORY_STORAGE_KEY = "adcheck_scan_histories";
export const CURRENT_PAGE_TITLE = "프리미엄 눈 건강 루테인 지아잔틴 1000mg";

export const CURRENT_PAGE_URL = "https://example.com/product/12345";

export const DEFAULT_SCAN_HISTORIES: ScanHistoryItem[] = [
  {
    id: "h1",
    dateStr: "오늘 오후 10:20",
    productName: CURRENT_PAGE_TITLE,
    count: 10,
    level: "REVIEW",
  },
  {
    id: "h2",
    dateStr: "오늘 오후 6:05",
    productName: "초고함량 식물성 알티지 오메가3",
    count: 0,
    level: "SAFE",
  },
  {
    id: "h3",
    dateStr: "오늘 오후 3:15",
    productName: "관절엔 초록잎홍합 & MSM 콤플렉스",
    count: 2,
    level: "CAUTION",
  },
  {
    id: "h4",
    dateStr: "오늘 오전 11:40",
    productName: "간 건강 밀크씨슬 실리마린 800mg",
    count: 7,
    level: "REVIEW",
  },
  {
    id: "h5",
    dateStr: "오늘 오전 9:05",
    productName: "체지방 감소 가르시니아 컴플렉스",
    count: 1,
    level: "CAUTION",
  },
];

export const MOCK_FINDINGS: FindingWithKeyword[] = [
  {
    keyword: "노안·백내장 근본 예방 및 시력 100% 완벽 회복 보장 특급 솔루션",
    bubbleLabel: "노안·백내장 예방",
    sourceText: "본 영양제는 단 2주일 만에 노안과 백내장을 근본적으로 예방하고 시력을 100% 완벽히 회복시켜 드립니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음",
    selector: "p.claim-1",
  },
  {
    keyword: "손상된 간세포 즉각 재생",
    bubbleLabel: "간세포 즉각 재생",
    sourceText: "잦은 음주로 극심하게 파괴된 간세포를 혁신적으로 즉각 재생시켜 줍니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "간 건강에 도움을 줄 수 있음",
    selector: "p.claim-2",
  },
  {
    keyword: "만성 관절염 완치",
    bubbleLabel: "관절염 완치",
    sourceText: "시큰거리는 퇴행성 관절염 통증을 며칠 만에 깨끗하게 완치 보장합니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "관절 및 연골건강에 도움을 줄 수 있음",
    selector: "p.claim-3",
  },
  {
    keyword: "혈관 핏떡 100% 융해",
    bubbleLabel: "혈전 100% 융해",
    sourceText: "혈액 속 뭉친 혈전과 핏떡을 100% 녹여내어 뇌졸중을 막아줍니다.",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "혈중 중성지질 개선·혈행개선에 도움을 줄 수 있음",
    selector: "p.claim-4",
  },
  {
    keyword: "체지방 100% 완전 분해",
    bubbleLabel: "체지방 100% 완전 분해",
    sourceText: "운동이나 식단 조절 전혀 없이도 섭취된 탄수화물과 체지방을 100% 태웁니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "탄수화물이 지방으로 합성되는 것을 억제하여 체지방 감소에 도움을 줄 수 있음",
    selector: "p.claim-5",
  },
  {
    keyword: "기적의 활력 부스터",
    bubbleLabel: "피로 즉각 해소",
    sourceText: "먹자마자 3초 만에 만성 피로가 즉각 날아가는 기적의 에너지 폭탄",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "피로개선에 도움을 줄 수 있음",
    selector: "p.claim-6",
  },
  {
    keyword: "단 3일 7kg 감량 보장",
    bubbleLabel: "3일 7kg 감량 보장",
    sourceText: "임상 증명 완료! 3일간 섭취하면 무조건 체중 7kg 감량을 보장해 드립니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "체지방 감소에 도움을 줄 수 있음",
    selector: "p.claim-7",
  },
  {
    keyword: "일일 권장량 1000% 배합",
    bubbleLabel: "고단위 1000% 배합",
    sourceText: "시중 제품과는 차원이 다른 슈퍼 고단위 압축 배합으로 효과가 10배 뛰어납니다.",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "영양소 보충 및 건강 증진에 도움을 줄 수 있음",
    selector: "p.claim-8",
  },
  {
    keyword: "전문의 만장일치 보증",
    bubbleLabel: "전문의 효과 보증",
    sourceText: "대한민국 최고 권위 전문의들이 직접 효과를 보증하고 만장일치로 추천한 제품",
    message: "과장 광고",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "건강기능식품 공통 기준",
    selector: "p.claim-9",
  },
  {
    keyword: "초고속 면역력 급상승",
    bubbleLabel: "면역력 급상승",
    sourceText: "감기 바이러스를 단숨에 사멸시키는 최강의 면역 코팅제",
    message: "의약품 오인",
    riskLevel: "CAUTION",
    category: "FUNCTION_CLAIM",
    officialFunction: "면역기능 유지에 도움을 줄 수 있음",
    selector: "p.claim-10",
  },
];

