import type { FindingCategory, RiskLevel } from '../types/analysis';

export type SeverityType = 'HIGH' | 'CAUTION' | 'NORMAL';

export interface CategoryThemeInfo {
  label: string;
  severity: SeverityType;
  badgeBg: string;
  badgeText: string;
  badgeBorder: string;
  indicatorColor: string; // 카드 및 버블 좌측 라인 색상
}

// 1. 심각도 3단계별 디자인 시스템 컬러
const SEVERITY_COLORS: Record<SeverityType, Omit<CategoryThemeInfo, 'label' | 'severity'>> = {
  HIGH: {
    badgeBg: '#FFF1F2',
    badgeText: '#E11D48',
    badgeBorder: '#FDA4AF',
    indicatorColor: '#E11D48',
  },
  CAUTION: {
    badgeBg: '#FFF7ED',
    badgeText: '#EA580C',
    badgeBorder: '#FED7AA',
    indicatorColor: '#EA580C',
  },
  NORMAL: {
    badgeBg: '#F0FDF4',
    badgeText: '#16A34A',
    badgeBorder: '#BBF7D0',
    indicatorColor: '#16A34A',
  },
};

// 2. Rule Engine 71종 카테고리 전체 레퍼런스 데이터
export const JUDGMENT_CATEGORY_MAP: Record<string, { severity: SeverityType; label: string }> = {
  // 공통 규칙 (COMMON)
  DISEASE_PREVENTION: { severity: 'HIGH', label: '질병 예방 표방' },
  DISEASE_TREATMENT: { severity: 'HIGH', label: '질병 치료·완치 표방' },
  MEDICINE_CONFUSION: { severity: 'HIGH', label: '의약품 오인 우려' },
  DISEASE_INFO_LINK: { severity: 'CAUTION', label: '질병 정보 부당 연계' },
  FUNCTION_EXCEED: { severity: 'HIGH', label: '기능성 범위 초과' },
  OFFICIAL_FUNCTION: { severity: 'NORMAL', label: '공식 인정 기능성' },
  ABSOLUTE_EFFECT: { severity: 'HIGH', label: '100%·완벽 효과 과장' },
  RESULT_TIME_AMOUNT: { severity: 'HIGH', label: '감량 수치·기간 단정' },
  COMPLETE_SOLUTION: { severity: 'HIGH', label: '해결·완치 단정 표현' },
  INGREDIENT_100: { severity: 'CAUTION', label: '원료 100% 강조' },
  SUB_INGREDIENT_FUNCTION: { severity: 'HIGH', label: '부원료 효능 과장' },
  SUB_INGREDIENT_EMPHASIS: { severity: 'CAUTION', label: '부원료 과도 강조' },
  TESTIMONIAL: { severity: 'CAUTION', label: '체험기·후기 부당 인용' },
  EXPERT_ENDORSEMENT: { severity: 'HIGH', label: '전문가 추천·보증' },
  EXPERT_DEVELOPMENT: { severity: 'CAUTION', label: '전문가 개발 표방' },
  HUMAN_STUDY: { severity: 'CAUTION', label: '인체시험 결과 과장' },
  NONHUMAN_STUDY: { severity: 'CAUTION', label: '동물·시험관 실험 인용' },
  RESEARCH_DISTORTION: { severity: 'HIGH', label: '연구 결과 왜곡' },
  PATENT_MISUSE: { severity: 'CAUTION', label: '특허 효능 과장' },
  CERTIFICATION: { severity: 'CAUTION', label: '인증·수상 내역 과장' },
  UNFAIR_COMPARISON: { severity: 'HIGH', label: '부당 비교 광고' },
  SUPERLATIVE: { severity: 'CAUTION', label: '최고·최초 극상 표현' },
  MIXED_PRODUCT: { severity: 'CAUTION', label: '일반식품 오인 혼동' },
  OVERCONSUMPTION: { severity: 'HIGH', label: '과다 섭취 유도' },
  REQUIRED_IDENTITY: { severity: 'CAUTION', label: '필수 표시사항 누락' },
  REVIEW_STATUS: { severity: 'CAUTION', label: '사전심의 불일치' },
  FUNCTION_SYNERGY: { severity: 'HIGH', label: '효능 시너지 과장' },
  TARGET_SPECIALIZATION: { severity: 'CAUTION', label: '특정 대상 효능 한정' },
  ABSORPTION: { severity: 'CAUTION', label: '흡수율·생체이용 과장' },
  NATURAL_FREE: { severity: 'CAUTION', label: '천연·무첨가 강조' },

  // 원료별 특화 규칙 (INGREDIENT_SPECIFIC)
  VAGINAL_SCOPE: { severity: 'CAUTION', label: '여성·질 건강 오인' },
  ORIGIN_TARGET: { severity: 'CAUTION', label: '질 유래 균주 표방' },
  DISEASE_GUT: { severity: 'HIGH', label: '장 질환 치료 표방' },
  CFU: { severity: 'CAUTION', label: '투입균수 부당 강조' },
  INFANT: { severity: 'HIGH', label: '영유아 섭취 강조' },
  STRAIN_EVIDENCE: { severity: 'CAUTION', label: '균주 특성 과장' },
  VESSEL: { severity: 'HIGH', label: '혈관 질환 치료 표방' },
  OTHER_FUNCTION: { severity: 'HIGH', label: '기타 미인정 기능성' },
  GENERATION: { severity: 'CAUTION', label: '세대 구분 과장' },
  ALIAS: { severity: 'CAUTION', label: '원료 명칭 오인 유도' },
  COLD: { severity: 'HIGH', label: '감기·호흡기 질환 표방' },
  MENOPAUSE: { severity: 'CAUTION', label: '갱년기 증상 완화 과장' },
  CONCENTRATION: { severity: 'CAUTION', label: '고함량·고농축 과장' },
  MEDICAL_FORM: { severity: 'CAUTION', label: '의약품 유사 형태' },
  EASY_DIET: { severity: 'HIGH', label: '쉽고 빠른 감량 표방' },
  PERIOD: { severity: 'HIGH', label: '단기간 다이어트 과장' },
  WEIGHT_FAT: { severity: 'HIGH', label: '체지방 완전 분해 과장' },
  SATIETY_COFFEE: { severity: 'CAUTION', label: '식욕 억제·포만감 과장' },
  GLUCOSE_DIET: { severity: 'HIGH', label: '혈당 다이어트 표방' },
  FATIGUE: { severity: 'CAUTION', label: '피로 회복 단정' },
  LIVER_MARKER: { severity: 'CAUTION', label: '간 수치 개선 과장' },
  ALCOHOL: { severity: 'HIGH', label: '숙취 해소·음주 전후 표방' },
  REGEN_CANCER: { severity: 'HIGH', label: '간 재생·암 예방 표방' },
  WEIGHT_RESULT: { severity: 'HIGH', label: '체중 감량 체험기 과장' },
  DETOX: { severity: 'HIGH', label: '디톡스·독소 배출 과장' },
  DIET_DRUG: { severity: 'CAUTION', label: '다이어트 의약품 오인' },
  ANTIAGING: { severity: 'HIGH', label: '노화 방지 표방' },
  PAIN: { severity: 'HIGH', label: '관절·신체 통증 완화 표방' },
  BODY_AREA: { severity: 'CAUTION', label: '특정 부위 효능 표방' },
  PLANT_ORIGIN: { severity: 'CAUTION', label: '식물성 기원 과도 강조' },
  SEASON: { severity: 'CAUTION', label: '계절성 질환 연계' },
  SEXUAL: { severity: 'HIGH', label: '성기능 개선 오인 표방' },
  ENERGY_EXPANSION: { severity: 'CAUTION', label: '활력·체력 증진 과장' },
  IMMUNE_INFLAMMATION: { severity: 'HIGH', label: '면역 질환·염증 치료' },
  VIRUS: { severity: 'CAUTION', label: '바이러스 차단 과장' },
  OTHER_ORAL: { severity: 'HIGH', label: '구강 질환 치료 표방' },
  ARTEPILLIN: { severity: 'CAUTION', label: '특정 지표성분 과장' },
  ANTIBACTERIAL_WORD: { severity: 'CAUTION', label: '항균 표현 오용' },
  VISION: { severity: 'HIGH', label: '시력 개선·회복 표방' },
  UV: { severity: 'HIGH', label: '자외선 완벽 차단 과장' },
  EYE_DISEASE: { severity: 'HIGH', label: '안과 질환 치료 표방' },
};

// 3. Fallback이 완벽히 보장되는 테마 조회 유틸 함수
export const getCategoryTheme = (category: string | undefined): CategoryThemeInfo => {
  const matched = category ? JUDGMENT_CATEGORY_MAP[category] : undefined;

  // DB에서 새로운 카테고리가 추가되어 맵에 없더라도 기본값(CAUTION)으로 안전 처리
  const severity: SeverityType = matched ? matched.severity : 'CAUTION';
  const label = matched ? matched.label : (category || '주의 문구');
  const colors = SEVERITY_COLORS[severity];

  return {
    label,
    severity,
    ...colors,
  };
};

// 참고용 타입 재노출: 컴포넌트에서 FindingCategory/RiskLevel을 함께 다룰 때 이 파일만 임포트해도 되도록.
export type { FindingCategory, RiskLevel };
