
// ==========================================
// 더미 데이터 인터페이스 및 기본값
// ==========================================
import React, { useState } from 'react';

export interface ViolationItem {
  id: string;
  category: 'MEDICINE' | 'EXAGGERATION';
  categoryLabel: string;
  keyword: string;
  adText: string;
  officialStandard: string;
  tip: string;
}

const DEFAULT_ITEMS: ViolationItem[] = [
  {
    id: '1',
    category: 'MEDICINE',
    categoryLabel: '의약품 오인 우려',
    keyword: '염증 완화 효능',
    adText: '“만성 염증과 관절 통증을 깨끗하게 치료해줍니다”',
    officialStandard: '건강기능식품은 질병의 예방 및 치료를 위한 의약품이 아닙니다. (식약처 고시)',
    tip: '질병명(염증, 관절염) 및 치료/완화 표현은 사용할 수 없어요.',
  },
  {
    id: '2',
    category: 'MEDICINE',
    categoryLabel: '의약품 오인 우려',
    keyword: '암세포 억제 효과',
    adText: '“면역세포를 활성화하여 암세포 성장을 억제하는 효능”',
    officialStandard: '신체 조직 기능의 영양 공급에 대한 표현만 인정됩니다.',
    tip: '특정 중증 질병의 억제 및 직접적인 면역 치료 언급은 불가해요.',
  },
  {
    id: '3',
    category: 'EXAGGERATION',
    categoryLabel: '과장 표현',
    keyword: '100% 흡수율',
    adText: '“체내 흡수율 100%! 먹는 즉시 몸속 끝까지 흡수”',
    officialStandard: '인체 흡수율 100%에 대한 객관적·과학적 임상 근거 부재.',
    tip: '‘100%’, ‘완벽’ 등 과학적으로 입증되지 않은 절대적 수치는 시정 대상이에요.',
  },
  {
    id: '4',
    category: 'EXAGGERATION',
    categoryLabel: '과장 표현',
    keyword: '먹자마자 3kg 감량',
    adText: '“단 일주일 만에 운동 없이 체지방 3kg 감량 보장”',
    officialStandard: '단기간 체중 감량 보장성 표현 및 소비 유도 과장 광고 금지.',
    tip: '‘체지방 감소에 도움을 줄 수 있음’ 공인 기능성 표현으로 순화해야 해요.',
  },
];

// 카테고리 뱃지: 버블 뷰와 통일된 파우더리 톤
const getCategoryBadgeStyle = (category: ViolationItem['category']) =>
  category === 'MEDICINE'
    ? { background: '#FDE8E8', color: '#BE123C' }
    : { background: '#FFEDD5', color: '#C2410C' };

export interface DetailListViewProps {
  items?: ViolationItem[];
  onGoBack?: () => void;
  onShare?: () => void;
  onRecheck?: () => void;
  onReset?: () => void;
  [key: string]: any;
}

export const DetailListView: React.FC<DetailListViewProps> = ({
  items = DEFAULT_ITEMS,
  onShare,
  onRecheck,
  onReset,
}) => {
  // 카테고리 필터
  const [filter, setFilter] = useState<'ALL' | 'MEDICINE' | 'EXAGGERATION'>('ALL');

  // 아코디언 열려있는 항목 ID
  const [openIds, setOpenIds] = useState<Set<string>>(new Set([items[0]?.id || '1']));

  const filteredItems = items.filter((item) => {
    if (filter === 'ALL') return true;
    return item.category === filter;
  });

  const toggleAccordion = (id: string) => {
    setOpenIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const medicineCount = items.filter((i) => i.category === 'MEDICINE').length;
  const exaggerationCount = items.filter((i) => i.category === 'EXAGGERATION').length;

  // 필터 칩 공통 스타일 (전체: 다크 슬레이트 모노톤, 도톰한 터치감)
  const filterChipStyle = (active: boolean): React.CSSProperties => ({
    padding: '8px 14px',
    borderRadius: '20px',
    border: 'none',
    fontSize: '12.5px',
    fontWeight: 700,
    cursor: 'pointer',
    whiteSpace: 'nowrap',
    background: active ? '#0F172A' : '#F1F5F9',
    color: active ? '#FFFFFF' : '#64748B',
    transition: 'all 0.15s ease',
  });

  // 카테고리 필터 칩: 버블 뷰와 통일된 파우더리 톤
  const categoryChipStyle = (category: ViolationItem['category'], active: boolean): React.CSSProperties => ({
    padding: '8px 14px',
    borderRadius: '20px',
    border: 'none',
    fontSize: '12.5px',
    fontWeight: 700,
    cursor: 'pointer',
    whiteSpace: 'nowrap',
    transition: 'all 0.15s ease',
    ...(active ? getCategoryBadgeStyle(category) : { background: '#F8FAFC', color: '#64748B' }),
  });

  const handleRecheck = onRecheck || onReset || (() => {});

  return (
    <div
      className="tab-panel"
      style={{
        width: '100%',
        minHeight: '100%',
        padding: '18px 16px 20px',
        boxSizing: 'border-box',
        animation: 'fadeIn 0.25s ease',
        display: 'flex',
        flexDirection: 'column',
        background: '#FFFFFF',
        borderRadius: '20px',
        border: '1px solid #F1F5F9',
        boxShadow: '0 4px 16px rgba(0, 0, 0, 0.03)',
      }}
    >

      {/* 1. 상단 타이틀 & 설명 앵커 */}
      <div style={{ marginBottom: '2px' }}>
        <h2 style={{ fontSize: '20px', fontWeight: 800, color: '#0F172A', margin: '0 0 4px 0', letterSpacing: '-0.4px', lineHeight: 1.35 }}>
          광고 점검 상세 리포트
        </h2>
        <p style={{ fontSize: '13px', color: '#64748B', margin: 0, lineHeight: 1.45, wordBreak: 'keep-all' }}>
          총 {items.length}건의 표현에 대해 식약처 기준을 확인해보세요.
        </p>
      </div>

      {/* 2. 카테고리 필터 칩 */}
      <div style={{ display: 'flex', gap: '8px', overflowX: 'auto', margin: '12px 0 16px' }}>
        <button type="button" onClick={() => setFilter('ALL')} style={filterChipStyle(filter === 'ALL')}>
          전체 {items.length}
        </button>
        <button type="button" onClick={() => setFilter('MEDICINE')} style={categoryChipStyle('MEDICINE', filter === 'MEDICINE')}>
          의약품 오인 우려 {medicineCount}
        </button>
        <button type="button" onClick={() => setFilter('EXAGGERATION')} style={categoryChipStyle('EXAGGERATION', filter === 'EXAGGERATION')}>
          과장 표현 {exaggerationCount}
        </button>
      </div>

      {/* 3. 슬림 아코디언 목록 */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', flex: '1 1 auto' }}>
        {filteredItems.map((item, idx) => {
          const isOpen = openIds.has(item.id);

          return (
            <div
              key={item.id}
              className="accordion-row-cascade"
              style={{
                background: '#FFFFFF',
                borderRadius: '14px',
                border: '1px solid #E2E8F0',
                boxShadow: '0 4px 12px rgba(15, 23, 42, 0.05)',
                overflow: 'hidden',
                animationDelay: `${idx * 0.05}s`,
              }}
            >
              <div
                onClick={() => toggleAccordion(item.id)}
                style={{
                  padding: '13px 14px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  cursor: 'pointer',
                  background: isOpen ? '#F8FAFC' : '#FFFFFF',
                  transition: 'background 0.2s ease',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flex: 1, minWidth: 0 }}>
                  <span
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      width: '22px',
                      height: '22px',
                      borderRadius: '7px',
                      fontSize: '11px',
                      fontWeight: 700,
                      flexShrink: 0,
                      ...getCategoryBadgeStyle(item.category),
                    }}
                  >
                    {idx + 1}
                  </span>
                  <span style={{ fontSize: '14px', fontWeight: 500, color: '#0F172A', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {item.keyword}
                  </span>
                </div>

                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="#94A3B8"
                  strokeWidth="2.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  style={{
                    flexShrink: 0,
                    marginLeft: '6px',
                    transform: isOpen ? 'rotate(180deg)' : 'rotate(0deg)',
                    transition: 'transform 0.35s cubic-bezier(0.34, 1.56, 0.64, 1)',
                  }}
                >
                  <polyline points="6 9 12 15 18 9" />
                </svg>
              </div>

              {/* 그리드 트랙 높이 자체를 스프링으로 튕겨 열고 닫는 트릭 */}
              <div
                style={{
                  display: 'grid',
                  gridTemplateRows: isOpen ? '1fr' : '0fr',
                  transition: 'grid-template-rows 0.45s cubic-bezier(0.34, 1.56, 0.64, 1)',
                }}
              >
                <div
                  style={{
                    overflow: 'hidden',
                    opacity: isOpen ? 1 : 0,
                    transform: isOpen ? 'translateY(0) scale(1)' : 'translateY(-6px) scale(0.97)',
                    transition:
                      'opacity 0.35s cubic-bezier(0.34, 1.56, 0.64, 1), transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1)',
                  }}
                >
                  <div style={{ padding: '4px 14px 14px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {/* 광고 속 문제 문구: 소프트 로즈 + 코랄 라인 인디케이터 */}
                    <div style={{ background: '#FFF1F2', borderRadius: '8px', padding: '10px 11px', borderLeft: '3px solid #F43F5E' }}>
                      <div style={{ fontSize: '11px', fontWeight: 700, color: '#E11D48', marginBottom: '3px' }}>광고 본문</div>
                      <div style={{ fontSize: '13px', color: '#0F172A', fontWeight: 700, lineHeight: 1.45 }}>{item.adText}</div>
                    </div>

                    {/* 식약처 공식 기준: 소프트 슬레이트 + 틸/민트 라인 인디케이터 */}
                    <div style={{ background: '#F8FAFC', borderRadius: '8px', padding: '10px 11px', borderLeft: '3px solid #10B981' }}>
                      <div style={{ fontSize: '11px', fontWeight: 700, color: '#059669', marginBottom: '3px' }}>식약처 고시 기준</div>
                      <div style={{ fontSize: '12px', color: '#334155', fontWeight: 400, lineHeight: 1.45 }}>{item.officialStandard}</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* 4. 하단 액션 버튼: 다른 광고 검사하기(프라이머리) + 결과 복사하기(아웃라인) */}
      <div style={{ marginTop: '20px', paddingTop: '2px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <button
          type="button"
          onClick={handleRecheck}
          style={{
            width: '100%',
            height: '47px',
            borderRadius: '16px',
            background: '#0F172A',
            border: 'none',
            color: '#FFFFFF',
            fontSize: '15px',
            fontWeight: 500,
            letterSpacing: '-0.2px',
            cursor: 'pointer',
          }}
        >
          다른 광고 검사하기
        </button>
        <button
          type="button"
          onClick={onShare}
          style={{
            width: '100%',
            height: '44px',
            borderRadius: '16px',
            background: '#FFFFFF',
            border: '1.5px solid #E2E8F0',
            color: '#334155',
            fontSize: '14px',
            fontWeight: 500,
            letterSpacing: '-0.2px',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '6px',
          }}
        >
          📋 결과 텍스트 복사하기
        </button>
      </div>

    </div>
  );
};

export default DetailListView;
