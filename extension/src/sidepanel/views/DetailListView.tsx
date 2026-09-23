
// ==========================================
// 상세 리포트 아코디언 목록 화면
// ==========================================
import React, { useEffect, useRef, useState } from 'react';
import type { FilterCategory, FindingWithKeyword } from '../types';
import type { FindingSource, Rule } from '../../types/analysis';
import { getCategoryTheme } from '../../constants/judgmentCategories';

// "주의가 필요한 이유" 박스 좌측의 물음표 곰돌이 아이콘. MoodFace.tsx와 동일한 방식으로 확장 아이콘 경로를 구함
const BEAR_QUESTION_ICON_URL =
  typeof chrome !== 'undefined' && chrome.runtime?.getURL
    ? chrome.runtime.getURL('icons/bear-question.png')
    : '/icons/bear-question.png';

// 백엔드 DB의 sourceUrl 필드 하나에 "URL (조문 설명); URL (조문 설명)"처럼 URL과 설명이 세미콜론(;)으로
// 여러 개 이어붙어 있을 수 있다. 세미콜론으로 쪼갠 뒤 각 조각 끝의 (설명)만 정규식으로 떼어내
// { url, label } 쌍의 배열로 변환한다. 괄호 패턴이 없으면 URL 전체를 label: null로 반환한다.
function parseSourceUrls(sourceUrl: string): { url: string; label: string | null }[] {
  return sourceUrl
    .split(';')
    .map((chunk) => chunk.trim())
    .filter((chunk) => chunk.length > 0)
    .map((chunk) => {
      const match = chunk.match(/^(.*?)\s*\(([^)]+)\)\s*$/);
      if (match) {
        return { url: match[1].trim(), label: match[2].trim() };
      }
      return { url: chunk, label: null };
    });
}

// 법령 정식 명칭 -> 법제처 공식 약칭 치환표. title에 포함돼 있으면 앞부분만 부분 치환한다.
const LAW_TITLE_ABBREVIATIONS: [string, string][] = [
  ['식품 등의 표시·광고에 관한 법률 시행령', '식품표시광고법 시행령'],
  ['식품 등의 표시·광고에 관한 법률', '식품표시광고법'],
];

// 근거 법령 1줄 축약: 공식 약칭으로 치환한 뒤, "제N조"까지만 남기고 그 뒤에 붙는 [별표 1],
// 제1항제1호 관련 같은 부속 조항은 잘라내 20자 내외 한 줄로 정리한다.
function abbreviateLawTitle(title: string): string {
  let short = title;
  for (const [full, abbr] of LAW_TITLE_ABBREVIATIONS) {
    if (short.includes(full)) {
      short = short.replace(full, abbr);
      break;
    }
  }
  const match = short.match(/^(.*?제\d+조(?:의\d+)?)/);
  return (match ? match[1] : short).trim();
}

// 여러 규칙의 message를 카테고리명 없이 하나의 자연스러운 줄글(문단)로 이어붙인다.
// 각 message 끝에 마침표가 없으면 붙여서 문장처럼 읽히게 한다.
function buildMultiRuleReason(rules: Rule[]): string {
  return rules
    .map((rule) => rule.message.trim())
    .filter(Boolean)
    .map((msg) => (/[.!?]$/.test(msg) ? msg : `${msg}.`))
    .join(' ');
}

// 대표 근거 법령 딱 1줄: 이중 토글 없이 sources[0]만 축약해서 보여준다.
// sourceUrl이 세미콜론으로 여러 URL을 이어붙이고 있어도 첫 번째 URL 하나만 사용한다.
function RepresentativeSourceLink({ source }: { source: FindingSource }) {
  const shortTitle = abbreviateLawTitle(source.title);
  if (!source.sourceUrl) {
    return <span className="law-ref-link law-ref-link--plain">근거: {shortTitle}</span>;
  }
  const [firstEntry] = parseSourceUrls(source.sourceUrl);
  return (
    <a href={firstEntry.url} target="_blank" rel="noopener noreferrer" className="law-ref-link">
      근거: {shortTitle} ↗
    </a>
  );
}

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

// 방어 코드: 마이그레이션 이전의 구버전 목업/API 응답은 rules 배열 대신 message/riskLevel/category 등을
// finding에 바로 담고 있을 수 있다. rules가 비어 있으면 그 구버전 단일 속성들을 rules[0] 하나짜리 배열로 감싸
// 화면이 깨지지 않게 한다.
function getRules(finding: FindingWithKeyword): Rule[] {
  if (finding.rules?.length) return finding.rules;
  const legacy = finding as unknown as Rule;
  if (legacy.message && legacy.riskLevel && legacy.category) {
    return [
      {
        message: legacy.message,
        riskLevel: legacy.riskLevel,
        category: legacy.category,
        officialFunction: legacy.officialFunction ?? null,
        sources: legacy.sources,
      },
    ];
  }
  return [];
}

// 규칙 하나의 message("의약품" 포함 여부)로 2대 구분 버킷(기능성 표시/광고 심의)을 매핑
function getRuleBucket(rule: Rule): 'DISEASE' | 'GUARANTEE' {
  return rule.message.includes('의약품') ? 'DISEASE' : 'GUARANTEE';
}

// 목록 헤더의 대표 배지/타이틀 표시용: 대표 규칙(rules[0]) 기준 버킷
function getFindingBucket(finding: FindingWithKeyword): 'DISEASE' | 'GUARANTEE' {
  const primary = getRules(finding)[0];
  return primary ? getRuleBucket(primary) : 'GUARANTEE';
}

// 필터 탭용: 대표 규칙뿐 아니라 함께 걸린 규칙 중 하나라도 해당 버킷에 속하면 필터 결과에 포함
function findingMatchesBucket(finding: FindingWithKeyword, bucket: 'DISEASE' | 'GUARANTEE'): boolean {
  return getRules(finding).some((rule) => getRuleBucket(rule) === bucket);
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

// "감지된 문구" 우측 상단 카테고리 배지. 추가로 매핑된 규칙이 있으면(extraRules.length > 0)
// "[기능성 표시 +N ▾]" 형태로 표시하고, 클릭 시 배지 바로 아래(우측 정렬)에 나머지 규칙 목록을
// 미니 팝오버로 띄운다. 380px 사이드패널 폭을 벗어나지 않도록 right: 0으로 고정.
function CategoryBadge({
  label,
  extraRules,
  isOpen,
  onToggle,
  registerRef,
}: {
  label: string;
  extraRules: Rule[];
  isOpen: boolean;
  onToggle: () => void;
  registerRef: (el: HTMLDivElement | null) => void;
}) {
  const hasExtra = extraRules.length > 0;

  return (
    <div ref={registerRef} style={{ position: 'relative', flexShrink: 0 }}>
      <button
        type="button"
        onClick={hasExtra ? onToggle : undefined}
        aria-expanded={hasExtra ? isOpen : undefined}
        className="category-badge"
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: '3px',
          background: '#F1F5F9',
          color: '#475569',
          fontSize: '11px',
          fontWeight: 700,
          padding: '2px 6px',
          borderRadius: '4px',
          border: 'none',
          cursor: hasExtra ? 'pointer' : 'default',
        }}
      >
        {label}
        {hasExtra && ` +${extraRules.length}`}
        {hasExtra && (
          <span
            aria-hidden="true"
            style={{
              display: 'inline-block',
              fontSize: '9px',
              transition: 'transform 0.2s ease',
              transform: isOpen ? 'rotate(180deg)' : 'rotate(0deg)',
            }}
          >
            ▾
          </span>
        )}
      </button>

      {hasExtra && (
        <div
          style={{
            position: 'absolute',
            top: 'calc(100% + 6px)',
            right: 0,
            zIndex: 20,
            minWidth: '170px',
            maxWidth: '220px',
            background: '#FFFFFF',
            border: '1px solid #E2E8F0',
            borderRadius: '10px',
            padding: '10px 12px',
            boxShadow: '0 10px 24px rgba(15, 23, 42, 0.16)',
            opacity: isOpen ? 1 : 0,
            transform: isOpen ? 'translateY(0) scale(1)' : 'translateY(-6px) scale(0.97)',
            pointerEvents: isOpen ? 'auto' : 'none',
            transition: 'opacity 0.2s cubic-bezier(0.34, 1.56, 0.64, 1), transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1)',
          }}
        >
          <div style={{ fontSize: '11px', fontWeight: 700, color: '#0F172A', marginBottom: '6px' }}>
            함께 위반된 규칙
          </div>
          <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: '5px' }}>
            {extraRules.map((rule, ruleIdx) => {
              const ruleTheme = getCategoryTheme(rule.category);
              const source = rule.sources?.[0];
              const firstUrl = source?.sourceUrl ? parseSourceUrls(source.sourceUrl)[0]?.url : null;
              const dotColor =
                ruleTheme.severity === 'HIGH' ? '#FA4224' : ruleTheme.severity === 'CAUTION' ? '#FDDC5C' : '#94A3B8';
              return (
                <li
                  key={ruleIdx}
                  style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11.5px', color: '#334155', lineHeight: 1.4 }}
                >
                  <span aria-hidden="true" style={{ width: '6px', height: '6px', borderRadius: '50%', flexShrink: 0, background: dotColor }} />
                  <span style={{ flex: '1 1 auto', minWidth: 0 }}>
                    {ruleTheme.label}
                    {firstUrl && source && (
                      <>
                        {' '}
                        (
                        <a
                          href={firstUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          style={{ color: '#0D9488', textDecoration: 'underline', textUnderlineOffset: '2px' }}
                        >
                          {abbreviateLawTitle(source.title)} ↗
                        </a>
                        )
                      </>
                    )}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}

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
    return findingMatchesBucket(finding, activeFilter);
  });

  const diseaseCount = findings.filter((finding) => findingMatchesBucket(finding, 'DISEASE')).length;
  const guaranteeCount = findings.filter((finding) => findingMatchesBucket(finding, 'GUARANTEE')).length;

  // 심각도 기준 안내 토글창 열림 상태
  const [isInfoOpen, setIsInfoOpen] = useState(false);

  // "함께 감지된 규칙" 배지 팝오버: 한 번에 하나만 열리도록 idx 하나만 추적
  const [openBadgeIdx, setOpenBadgeIdx] = useState<number | null>(null);
  const badgeRefs = useRef<Map<number, HTMLDivElement>>(new Map());

  // 팝오버 바깥 클릭 시 닫기
  useEffect(() => {
    if (openBadgeIdx === null) return;
    function handleClickOutside(event: MouseEvent) {
      const container = badgeRefs.current.get(openBadgeIdx as number);
      if (container && !container.contains(event.target as Node)) {
        setOpenBadgeIdx(null);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [openBadgeIdx]);

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
      <div style={{ display: 'flex', gap: '8px', justifyContent: 'center', overflowX: 'auto', margin: '12px 0 16px' }}>
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
          // 같은 문장(Claim)에 여러 규칙이 매핑될 수 있어, 메인 카드 콘텐츠는 대표 규칙인 rules[0] 기준으로 표시
          const rules = getRules(finding);
          const primaryRule = rules[0];
          if (!primaryRule) return null;
          const theme = getCategoryTheme(primaryRule.category);
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
                    {rules.length > 1 && ` 외 ${rules.length - 1}건`}
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
                    // 이 항목의 배지 팝오버가 열려 있을 때는 팝오버가 잘리지 않도록 clip을 잠깐 풀어준다
                    overflow: openBadgeIdx === idx ? 'visible' : 'hidden',
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
                        overflow: openBadgeIdx === idx ? 'visible' : 'hidden',
                      }}
                    >
                      {/* 상단 헤더: "감지된 문구" 라벨 + 위반 유형 뱃지(2대 구분 버킷)를 한 줄(flex space-between)에 배치해
                          줄바꿈으로 생기던 상단 여백을 없애고, 바로 아래에 실제 광고 문구 전체를 바짝 붙임 */}
                      <div style={{ padding: '12px 14px 11px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '6px' }}>
                          <span style={{ fontSize: '13.5px', fontWeight: 700, color: '#0F172A', letterSpacing: '-0.2px' }}>
                            감지된 문구
                          </span>
                          <CategoryBadge
                            label={FINDING_BUCKET_LABEL[getFindingBucket(finding)]}
                            extraRules={rules.slice(1)}
                            isOpen={openBadgeIdx === idx}
                            onToggle={() => setOpenBadgeIdx((prev) => (prev === idx ? null : idx))}
                            registerRef={(el) => {
                              if (el) badgeRefs.current.set(idx, el);
                              else badgeRefs.current.delete(idx);
                            }}
                          />
                        </div>
                        <div style={{ fontSize: '13px', color: '#0F172A', lineHeight: 1.5 }}>
                          "{finding.sourceText}"
                        </div>
                      </div>

                      {/* 주의가 필요한 이유: 규칙이 1개면 대표 규칙(rules[0])의 설명 문장을 그대로,
                          여러 개면 각 규칙의 message를 카테고리명 없이 하나의 줄글(문단)로 이어붙인다.
                          어차피 AI가 추출한 문장들이라 라벨 없이도 자연스럽게 읽힌다.
                          근거 법령은 대표 규칙(rules[0]) 1줄만 이중 토글 없이 노출 */}
                      {(rules.length > 1 || primaryRule.message) && (
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
                              {rules.length > 1 ? buildMultiRuleReason(rules) : primaryRule.message}
                            </div>
                          </div>

                          {/* 대표 근거 법령 딱 1줄: 토글 없이 sources[0]만 약칭으로 축약해서 노출.
                              아직 목업/실제 API 모두 sources가 없는 경로가 있을 수 있어 옵셔널 체이닝으로 방어 */}
                          {primaryRule.sources?.length ? (
                            <div className="law-ref-list">
                              <RepresentativeSourceLink source={primaryRule.sources[0]} />
                            </div>
                          ) : null}
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
