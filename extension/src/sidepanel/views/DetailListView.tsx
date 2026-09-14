
// ==========================================
// 상세 리포트 아코디언 목록 화면
// ==========================================
import React, { useState } from 'react';
import type { FindingWithKeyword } from '../types';
import { getCategoryTheme } from '../../constants/judgmentCategories';

export interface DetailListViewProps {
  findings: FindingWithKeyword[];
  onShare?: () => void;
  onAnalyze?: () => void;
  onReset?: () => void;
  [key: string]: any;
}

export const DetailListView: React.FC<DetailListViewProps> = ({
  findings,
  onShare,
  onAnalyze,
  onReset,
}) => {
  // 카테고리 필터: 'ALL' 또는 findings에 실제로 등장하는 category 값(문자열).
  // Rule Engine 카테고리가 71종+로 열려있어 고정된 유니언 타입 대신 findings에서 동적으로 뽑아낸다.
  const [filter, setFilter] = useState<string>('ALL');

  // 아코디언 열려있는 항목의 원본 findings 배열 기준 인덱스
  // 진입 시에는 전부 닫힌 슬림 목록으로 시작해, 스캔 결과를 한눈에 훑을 수 있도록 합니다.
  const [openIndices, setOpenIndices] = useState<Set<number>>(new Set());

  const indexedFindings = findings.map((finding, idx) => ({ finding, idx }));

  const filteredFindings = indexedFindings.filter(({ finding }) => {
    if (filter === 'ALL') return true;
    return finding.category === filter;
  });

  const toggleAccordion = (idx: number) => {
    setOpenIndices((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx);
      else next.add(idx);
      return next;
    });
  };

  // 실제로 등장하는 카테고리만 필터 칩으로 노출 (등장하지 않는 카테고리는 칩을 만들지 않음)
  const categoryCounts = new Map<string, number>();
  findings.forEach((finding) => {
    categoryCounts.set(finding.category, (categoryCounts.get(finding.category) ?? 0) + 1);
  });
  const presentCategories = Array.from(categoryCounts.keys());

  // 필터 칩 공통 스타일: 흐릿한 무채색 대신 선택/비선택이 또렷하게 대비되는 스타일
  const filterChipStyle = (active: boolean): React.CSSProperties => ({
    padding: '8px 14px',
    borderRadius: '20px',
    fontSize: '12.5px',
    cursor: 'pointer',
    whiteSpace: 'nowrap',
    transition: 'all 0.15s ease',
    background: active ? '#0F172A' : '#FFFFFF',
    color: active ? '#FFFFFF' : '#64748B',
    fontWeight: active ? 700 : 600,
    border: active ? 'none' : '1.5px solid #CBD5E1',
  });

  const handleRecheck = onAnalyze || onReset || (() => {});

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
      <div style={{ marginBottom: '2px', textAlign: 'center' }}>
        <h2 style={{ fontSize: '20px', fontWeight: 800, color: '#190933', margin: '0 0 4px 0', letterSpacing: '-0.4px', lineHeight: 1.35 }}>
          광고 점검 상세 리포트
        </h2>
        <p style={{ fontSize: '13px', color: '#64748B', margin: 0, lineHeight: 1.45, wordBreak: 'keep-all' }}>
          총 {findings.length}건의 표현에 대해 식약처 기준을 확인해보세요.
        </p>
      </div>

      {/* 2. 카테고리 필터 칩: findings에 실제로 등장하는 category마다 하나씩 동적으로 생성 */}
      <div style={{ display: 'flex', gap: '8px', overflowX: 'auto', margin: '12px 0 16px' }}>
        <button type="button" onClick={() => setFilter('ALL')} style={filterChipStyle(filter === 'ALL')}>
          전체 {findings.length}
        </button>
        {presentCategories.map((category) => {
          const theme = getCategoryTheme(category);
          return (
            <button
              key={category}
              type="button"
              onClick={() => setFilter(category)}
              style={filterChipStyle(filter === category)}
            >
              {theme.label} {categoryCounts.get(category)}
            </button>
          );
        })}
      </div>

      {/* 3. 슬림 아코디언 목록 */}
      <div className="accordion-list-wrapper" style={{ display: 'flex', flexDirection: 'column', flex: '1 1 auto' }}>
        {filteredFindings.map(({ finding, idx }, displayIdx) => {
          const isOpen = openIndices.has(idx);
          const theme = getCategoryTheme(finding.category);

          return (
            <div
              key={finding.selector ?? idx}
              className="detail-accordion-item"
              style={{
                background: '#FFFFFF',
                borderRadius: '16px',
                border: '1.5px solid #E2E8F0',
                boxShadow: '0 4px 16px rgba(15, 23, 42, 0.05)',
                marginBottom: '14px',
                overflow: 'hidden',
                animationDelay: `${displayIdx * 0.055}s`,
              }}
            >
              <div
                onClick={() => toggleAccordion(idx)}
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
                      transition: 'background 0.2s ease, color 0.2s ease',
                      background: isOpen ? theme.badgeText : theme.badgeBg,
                      color: isOpen ? '#FFFFFF' : theme.badgeText,
                    }}
                  >
                    {displayIdx + 1}
                  </span>
                  <span style={{ fontSize: '14px', fontWeight: 500, color: '#190933', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {finding.bubbleLabel}
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
                    {/* 광고 속 문제 문구: 카테고리 테마 틴트 + 라인 인디케이터 + 카테고리 배지 칩 */}
                    <div style={{ background: theme.badgeBg, borderRadius: '8px', padding: '10px 11px', borderLeft: `3px solid ${theme.indicatorColor}` }}>
                      <span
                        className="category-badge"
                        style={{
                          display: 'inline-block',
                          backgroundColor: theme.badgeBg,
                          color: theme.badgeText,
                          border: `1px solid ${theme.badgeBorder}`,
                          borderRadius: '9999px',
                          padding: '2px 8px',
                          fontSize: '10.5px',
                          fontWeight: 700,
                          marginBottom: '5px',
                        }}
                      >
                        {theme.label}
                      </span>
                      <div style={{ fontSize: '13px', color: '#190933', fontWeight: 700, lineHeight: 1.45 }}>{finding.sourceText}</div>
                    </div>

                    {/* 식약처 공식 기준: 카테고리와 무관하게 항상 동일한 민트 틴트(상단 브랜드 컬러와 통일) */}
                    <div style={{ background: '#F2FDF9', borderRadius: '8px', padding: '10px 11px', borderLeft: '3px solid #5DD9C1' }}>
                      <div style={{ fontSize: '11px', fontWeight: 700, color: '#0D9488', marginBottom: '3px' }}>식약처 고시 기준</div>
                      <div style={{ fontSize: '12px', color: '#334155', fontWeight: 400, lineHeight: 1.45 }}>
                        {finding.officialFunction ?? '해당 표현에 대응하는 공인 기능성 문구가 없어요.'}
                      </div>
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
