
// ==========================================
// 상세 리포트 아코디언 목록 화면
// ==========================================
import React, { useEffect, useRef } from 'react';
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
  DISEASE: '기능성표시',
  GUARANTEE: '광고심의',
};

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

      {/* 2. 카테고리 필터 칩: 전체 / 기능성 표시 / 광고심의 2대 구분 */}
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
                      {/* 상단 헤더: 위반 유형 뱃지(2대 구분 버킷) + "감지된 문구" 라벨 + 실제 광고 문구 전체 */}
                      <div style={{ padding: '12px 14px 11px' }}>
                        <span
                          className="category-badge"
                          style={{
                            display: 'inline-block',
                            background: '#F1F5F9',
                            color: '#475569',
                            fontSize: '11px',
                            fontWeight: 700,
                            padding: '3px 7px',
                            borderRadius: '4px',
                          }}
                        >
                          {FINDING_BUCKET_LABEL[getFindingBucket(finding)]}
                        </span>
                        <div style={{ fontSize: '11.5px', fontWeight: 700, color: '#0F172A', letterSpacing: '-0.2px', marginTop: '8px' }}>
                          감지된 문구
                        </div>
                        <div style={{ fontSize: '13px', color: '#0F172A', lineHeight: 1.5, marginTop: '4px' }}>
                          "{finding.sourceText}"
                        </div>
                      </div>

                      {/* 주의가 필요한 이유: AI 요약 한 문장 설명(finding.message)을 옅은 에드체크 민트 톤 박스로 */}
                      {finding.message && (
                        <div style={{ padding: '11px 14px', borderTop: '1px solid #F1F5F9' }}>
                          <div style={{ fontSize: '12px', fontWeight: 700, color: '#0F172A', letterSpacing: '-0.2px' }}>
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
                              style={{ width: '32px', height: '32px', flexShrink: 0, objectFit: 'contain'}}
                            />
                            <div style={{ fontSize: '12.5px', fontWeight: 500, color: '#1E293B', lineHeight: 1.5 }}>
                              {finding.message}
                            </div>
                          </div>
                        </div>
                      )}

                      {/* 공식 인정 문구 + 근거 법령: 서로 다른 섹션으로 분리하지 않고,
                          문구가 끝나는 바로 아래에 근거가 이어지도록 한 블록으로 묶음.
                          실제 이동할 법령 원문 URL을 아직 확정하지 못해 <a href>는 붙이지 않고,
                          링크처럼 보이는 스타일(밑줄+호버)만 우선 적용 */}
                      <div style={{ padding: '11px 14px 12px', borderTop: '1px solid #F1F5F9' }}>
                        <div style={{ fontSize: '11.5px', fontWeight: 700, color: '#0F172A', letterSpacing: '-0.2px' }}>
                          공식 인정 문구
                        </div>
                        <div style={{ fontSize: '12.5px', color: '#334155', lineHeight: 1.5, marginTop: '4px' }}>
                          {finding.officialFunction ? `"${finding.officialFunction}"` : '해당 표현에 대응하는 공인 기능성 문구가 없어요.'}
                        </div>
                        <span className="law-ref-link" style={{ display: 'block', marginTop: '6px', fontSize: '11px' }}>
                          근거: 식품 등의 표시·광고에 관한 법률 ↗
                        </span>
                      </div>
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
        <button
          type="button"
          onClick={onReset}
          style={{
            width: '100%',
            height: '47px',
            borderRadius: '16px',
            background: '#190933',
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
      </div>

    </div>
  );
};

export default DetailListView;
