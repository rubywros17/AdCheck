
// ==========================================
// 상세 리포트 아코디언 목록 화면
// ==========================================
import React, { useEffect, useRef, useState } from 'react';
import type { FilterCategory, FindingWithKeyword } from '../types';
import { getCategoryTheme } from '../../constants/judgmentCategories';

// "주의가 필요한 이유" 박스 좌측의 물음표 곰돌이 아이콘. MoodFace.tsx와 동일한 방식으로 확장 아이콘 경로를 구함
const BEAR_QUESTION_ICON_URL =
  typeof chrome !== 'undefined' && chrome.runtime?.getURL
    ? chrome.runtime.getURL('icons/bear-question.png')
    : '/icons/bear-question.png';

export interface DetailListViewProps {
  findings: FindingWithKeyword[];
  activeFilter: FilterCategory;
  expandedFindings: Set<number>;
  pendingScrollIdx: number | null;
  currentPageTitle?: string;
  pageUrl?: string;
  isFavorite?: boolean;
  onFilterChange: (filter: FilterCategory) => void;
  onToggleFinding: (idx: number) => void;
  onScrollComplete: () => void;
  onLocateFinding?: (finding: FindingWithKeyword) => void;
  // "다른 광고 검사하기"는 바로 재분석하지 않고, "이 상품 광고 믿고 사도 될까요?" 버튼이 있는
  // IDLE 화면부터 다시 시작하도록 onReset(goHome)을 씁니다.
  onReset?: () => void;
  onToggleFavorite?: () => void;
  onBack?: () => void;
}

// finding.message("의약품" 포함 여부)로 2대 구분 버킷(기능성 표시/광고 심의)을 매핑
function getFindingBucket(finding: FindingWithKeyword): 'DISEASE' | 'GUARANTEE' {
  return finding.message.includes('의약품') ? 'DISEASE' : 'GUARANTEE';
}

// 토글 내부 뱃지에 쓰는 2대 구분 버킷 표시 문구 (71종 세부 카테고리 대신 큰 갈래만 보여줌)
const FINDING_BUCKET_LABEL: Record<'DISEASE' | 'GUARANTEE', string> = {
  DISEASE: '기능성 표시',
  GUARANTEE: '광고 심의',
};

// 심각도(2단계) 범례 & 정보 토글창에 쓰는 문구·색상.
// 앱 전체 등급 라벨(안심/검토/주의)과 혼동되지 않도록 높음/보통이라는 별도 단어를 씀. NORMAL(초록)은 위반이 아니라 범례에서 제외.
const SEVERITY_LEGEND_ITEMS: { key: 'HIGH' | 'CAUTION'; color: string; dotLabel: string; description: string }[] = [
  {
    key: 'HIGH',
    color: '#FA4224',
    dotLabel: '높음',
    description:
      '제품에 없는 효과를 기대하게 하거나, 치료·효과 보장으로 받아들이게 해 제품에 대한 판단을 크게 왜곡할 수 있는 표현',
  },
  {
    key: 'CAUTION',
    color: '#FDDC5C',
    dotLabel: '보통',
    description: '강조 방식이나 설명 부족으로 제품의 특성·근거·효과 범위를 오해하게 할 수 있는 표현',
  },
];

export const DetailListView: React.FC<DetailListViewProps> = ({
  findings,
  activeFilter,
  expandedFindings,
  pendingScrollIdx,
  currentPageTitle,
  pageUrl,
  isFavorite,
  onFilterChange,
  onToggleFinding,
  onScrollComplete,
  onLocateFinding,
  onReset,
  onToggleFavorite,
  onBack,
}) => {
  const indexedFindings = findings.map((finding, idx) => ({ finding, idx }));

  const filteredFindings = indexedFindings.filter(({ finding }) => {
    if (activeFilter === 'ALL') return true;
    return getFindingBucket(finding) === activeFilter;
  });

  const diseaseCount = findings.filter((finding) => getFindingBucket(finding) === 'DISEASE').length;
  const guaranteeCount = findings.length - diseaseCount;

  // 심각도 기준 안내 토글창 열림 상태
  const [isInfoOpen, setIsInfoOpen] = useState(false);

  // 말풍선 화면에서 특정 문구를 선택해 넘어온 경우, 그 항목으로 자동 스크롤
  const itemRefs = useRef<Map<number, HTMLDivElement>>(new Map());

  useEffect(() => {
    if (pendingScrollIdx === null) return;
    itemRefs.current.get(pendingScrollIdx)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    onScrollComplete();
  }, [pendingScrollIdx, onScrollComplete]);

  // 필터 칩 공통 스타일: 흐릿한 무채색 대신 선택/비선택이 또렷하게 대비되는 스타일
  const filterChipStyle = (active: boolean): React.CSSProperties => ({
    padding: '4px 12px',
    borderRadius: '20px',
    fontSize: '12.5px',
    cursor: 'pointer',
    whiteSpace: 'nowrap',
    transition: 'all 0.15s ease',
    background: active ? '#0F172A' : 'transparent',
    color: active ? '#FFFFFF' : '#64748B',
    fontWeight: active ? 700 : 600,
    border: active ? 'none' : '1px solid #E2E8F0',
  });

  return (
    <div
      className="tab-panel detail-list-container"
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
      <div style={{ position: 'relative', textAlign: 'center', marginBottom: '2px' }}>
        {onToggleFavorite && (
          <button
            type="button"
            onClick={onToggleFavorite}
            aria-label={isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
            aria-pressed={!!isFavorite}
            style={{
              position: 'absolute',
              top: 0,
              right: 0,
              width: '26px',
              height: '26px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'transparent',
              border: 'none',
              borderRadius: '50%',
              fontSize: '17px',
              lineHeight: 1,
              color: isFavorite ? '#F59E0B' : '#CBD5E1',
              cursor: 'pointer',
            }}
          >
            {isFavorite ? '★' : '☆'}
          </button>
        )}
        {/* 화살표(좌) / 제목(중앙) / 여백(우) 3칸 그리드로 나눠 화살표와 제목이 절대 겹치지 않게 함 */}
        <div style={{ display: 'grid', gridTemplateColumns: '22px 1fr 22px', alignItems: 'center', columnGap: '6px', width: '100%' }}>
          {onBack && (
            <button
              type="button"
              className="icon-back-btn"
              onClick={onBack}
              aria-label="뒤로가기"
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#0F172A" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="15 18 9 12 15 6" />
              </svg>
            </button>
          )}
          <h2 style={{ gridColumn: 2, fontSize: '20px', fontWeight: 800, color: '#190933', margin: 0, letterSpacing: '-0.4px', lineHeight: 1.35 }}>
            광고 점검 상세 리포트
          </h2>
        </div>
        {currentPageTitle && pageUrl && (
          <a
            href={pageUrl}
            target="_blank"
            rel="noopener noreferrer"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '4px',
              marginTop: '18px',
              fontSize: '12.5px',
              fontWeight: 700,
              color: '#0D9488',
              textDecoration: 'underline',
              textUnderlineOffset: '2px',
              wordBreak: 'keep-all',
            }}
          >
            {currentPageTitle}
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}>
              <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
              <polyline points="15 3 21 3 21 9" />
              <line x1="10" y1="14" x2="21" y2="3" />
            </svg>
          </a>
        )}
      </div>

      {/* 2. 카테고리 필터 칩: 전체 / 기능성 표시 / 광고 심의 2대 구분 */}
      <div style={{ display: 'flex', gap: '8px', overflowX: 'auto', margin: '12px 0 16px' }}>
        <button
          type="button"
          className="filter-chip-enter"
          onClick={() => onFilterChange('ALL')}
          style={{ ...filterChipStyle(activeFilter === 'ALL'), animationDelay: '0s' }}
        >
          전체 {findings.length}
        </button>
        <button
          type="button"
          className="filter-chip-enter"
          onClick={() => onFilterChange('DISEASE')}
          style={{ ...filterChipStyle(activeFilter === 'DISEASE'), animationDelay: '0.08s' }}
        >
          기능성 표시 {diseaseCount}
        </button>
        <button
          type="button"
          className="filter-chip-enter"
          onClick={() => onFilterChange('GUARANTEE')}
          style={{ ...filterChipStyle(activeFilter === 'GUARANTEE'), animationDelay: '0.16s' }}
        >
          광고 심의 {guaranteeCount}
        </button>
      </div>

      {/* 2-1. 심각도(2단계) 범례 + ⓘ 정보 토글: 빨간/노란 점이 각각 무슨 뜻인지 안내. 전체 우측 정렬, ⓘ가 "심각도" 라벨 왼쪽에 옴
          info 박스는 position:relative 기준점만 제공 — 아래 팝오버가 이 기준으로 절대 위치를 잡음 */}
      <div style={{ position: 'relative' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', padding: '6px 2px', marginBottom: '6px' }}>
          <button
            type="button"
            onClick={() => setIsInfoOpen((prev) => !prev)}
            aria-expanded={isInfoOpen}
            aria-label={isInfoOpen ? '심각도 기준 정보 닫기' : '심각도 기준 정보 보기'}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '22px',
              height: '22px',
              background: 'transparent',
              border: 'none',
              borderRadius: '50%',
              cursor: 'pointer',
              color: isInfoOpen ? '#0F172A' : '#94A3B8',
              transition: 'color 0.15s ease',
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="16" x2="12" y2="12" />
              <line x1="12" y1="8" x2="12.01" y2="8" />
            </svg>
          </button>
          <span style={{ fontSize: '11.5px', fontWeight: 700, color: '#64748B', marginLeft: '2px', marginRight: '8px' }}>심각도</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            {SEVERITY_LEGEND_ITEMS.map((item) => (
              <span key={item.key} style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                <span
                  aria-hidden="true"
                  style={{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', flexShrink: 0, background: item.color }}
                />
                <span style={{ fontSize: '11px', color: '#475569', fontWeight: 500 }}>{item.dotLabel}</span>
              </span>
            ))}
          </div>
        </div>

        {/* 심각도 기준 메모: 아래 상세 리포트 목록을 밀어내지 않도록, 목록 위에 뜨는 절대 위치 팝오버(메모) 카드로 표시 */}
        <div
          style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            right: 0,
            zIndex: 20,
            background: '#F8FAFC',
            border: '1px solid #E2E8F0',
            borderRadius: '10px',
            padding: '10px 12px',
            boxShadow: '0 10px 24px rgba(15, 23, 42, 0.16)',
            opacity: isInfoOpen ? 1 : 0,
            transform: isInfoOpen ? 'translateY(0) scale(1)' : 'translateY(-6px) scale(0.97)',
            pointerEvents: isInfoOpen ? 'auto' : 'none',
            transition: 'opacity 0.25s cubic-bezier(0.34, 1.56, 0.64, 1), transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1)',
          }}
        >
          <div style={{ fontSize: '12px', fontWeight: 700, color: '#0F172A', marginBottom: '8px' }}>
            💡 문구별 심각도 기준
          </div>
          {SEVERITY_LEGEND_ITEMS.map((item, itemIdx) => (
            <div
              key={item.key}
              style={{ display: 'flex', alignItems: 'flex-start', gap: '6px', marginTop: itemIdx === 0 ? 0 : '16px' }}
            >
              <span
                aria-hidden="true"
                style={{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', flexShrink: 0, marginTop: '4px', background: item.color }}
              />
              <div style={{ fontSize: '11px', color: '#64748B', lineHeight: 1.5 }}>
                <span style={{ fontWeight: 700, color: '#0F172A' }}>{item.dotLabel}: </span>
                {item.description}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* 3. 슬림 아코디언 목록: 항목마다 따로 떠 있던 박스를 하나로 이어붙인 리스트로 통일 */}
      <div
        className="accordion-list-wrapper"
        style={{
          display: 'flex',
          flexDirection: 'column',
          flex: '1 1 auto',
          background: '#FFFFFF',
          border: '1.5px solid #E2E8F0',
          borderRadius: '16px',
          boxShadow: '0 4px 16px rgba(15, 23, 42, 0.05)',
          overflow: 'hidden',
        }}
      >
        {filteredFindings.map(({ finding, idx }, displayIdx) => {
          const isOpen = expandedFindings.has(idx);
          const theme = getCategoryTheme(finding.category);
          const isLast = displayIdx === filteredFindings.length - 1;

          return (
            <div
              key={finding.selector ?? idx}
              ref={(el) => {
                if (el) itemRefs.current.set(idx, el);
                else itemRefs.current.delete(idx);
              }}
              className="detail-accordion-item"
              style={{
                background: '#FFFFFF',
                borderBottom: isLast ? 'none' : '1px solid #F1F5F9',
                animationDelay: `${displayIdx * 0.055}s`,
              }}
            >
              <div
                onClick={() => onToggleFinding(idx)}
                className={`detail-row-trigger${isOpen ? ' is-open' : ''}`}
                style={{
                  height: '48px',
                  padding: '0 14px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: '8px',
                  cursor: 'pointer',
                }}
              >
                {/* 한 줄 초간결 뷰: 위험도 컬러 도트 + 카테고리 타이틀 (배지/본문 미리보기는 토글 내부로 이동) */}
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flex: '1 1 auto', minWidth: 0 }}>
                  <span
                    aria-hidden="true"
                    style={{
                      display: 'inline-block',
                      width: '8px',
                      height: '8px',
                      borderRadius: '50%',
                      flexShrink: 0,
                      background: theme.indicatorColor,
                    }}
                  />
                  <span
                    style={{
                      fontSize: '14px',
                      fontWeight: 700,
                      color: '#0F172A',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {theme.label}
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
                  <div style={{ padding: '4px 14px 14px' }}>
                    {/* 단일 카드: 색색의 분절된 박스 대신 흰 카드 + 얇은 구분선으로 섹션을 나눔 */}
                    <div
                      style={{
                        background: '#FFFFFF',
                        border: '1px solid #E2E8F0',
                        borderRadius: '12px',
                        boxShadow: '0 2px 8px rgba(15, 23, 42, 0.04)',
                        overflow: 'hidden',
                      }}
                    >
                      {/* 상단 헤더: "감지된 문구" 라벨 + 위반 유형 뱃지(2대 구분 버킷)를 한 줄(flex space-between)에 배치해
                          줄바꿈으로 생기던 상단 여백을 없애고, 바로 아래에 실제 광고 문구 전체를 바짝 붙임 */}
                      <div style={{ padding: '12px 14px 11px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px' }}>
                          <span style={{ fontSize: '13.5px', fontWeight: 700, color: '#0F172A', letterSpacing: '-0.2px' }}>
                            감지된 문구
                          </span>
                          <span
                            className="category-badge"
                            style={{
                              display: 'inline-block',
                              background: '#F1F5F9',
                              color: '#475569',
                              fontSize: '11px',
                              fontWeight: 700,
                              padding: '2px 6px',
                              borderRadius: '4px',
                            }}
                          >
                            {FINDING_BUCKET_LABEL[getFindingBucket(finding)]}
                          </span>
                        </div>
                        <div style={{ fontSize: '13px', color: '#0F172A', lineHeight: 1.5 }}>
                          "{finding.sourceText}"
                        </div>
                      </div>

                      {/* 주의가 필요한 이유: AI 요약 한 문장 설명(finding.message)을 옅은 에드체크 민트 톤 박스로.
                          공식 인정 문구 섹션을 없애면서, 그 아래 있던 근거 법령 링크를 이 섹션 끝으로 올림.
                          실제 이동할 법령 원문 URL을 아직 확정하지 못해 <a href>는 붙이지 않고,
                          링크처럼 보이는 스타일(밑줄+호버)만 우선 적용 */}
                      {finding.message && (
                        <div style={{ padding: '11px 14px 12px', borderTop: '1px solid #F1F5F9' }}>
                          <div style={{ fontSize: '13.5px', fontWeight: 700, color: '#0F172A', letterSpacing: '-0.2px' }}>
                            주의가 필요한 이유
                          </div>
                          <div
                            style={{
                              marginTop: '6px',
                              background: '#F0FDFA',
                              border: '1px solid #CCFBF1',
                              borderRadius: '8px',
                              padding: '10px 12px',
                              display: 'flex',
                              alignItems: 'center',
                              gap: '10px',
                            }}
                          >
                            <img
                              src={BEAR_QUESTION_ICON_URL}
                              alt=""
                              aria-hidden="true"
                              style={{ width: '32px', height: '32px', flexShrink: 0, objectFit: 'contain' }}
                            />
                            <div style={{ fontSize: '12.5px', fontWeight: 500, color: '#1E293B', lineHeight: 1.5 }}>
                              {finding.message}
                            </div>
                          </div>
                          <span className="law-ref-link" style={{ display: 'block', marginTop: '6px', fontSize: '11px' }}>
                            근거: 식품 등의 표시·광고에 관한 법률 ↗
                          </span>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* 4. 하단 액션 버튼: 다른 광고 검사하기 -> "이 상품 광고 믿고 사도 될까요?" IDLE 화면부터 다시 시작 */}
      <div style={{ marginTop: '20px', paddingTop: '2px' }}>
        <button className="btn-brand-primary" type="button" onClick={onReset}>
          다른 광고 검사하기
        </button>
      </div>

    </div>
  );
};

export default DetailListView;
