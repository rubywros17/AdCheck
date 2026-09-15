import type { ReviewLevel } from '../types';

interface MoodFaceProps {
  level: ReviewLevel;
  size?: number;
}

// 등급별 곰돌이 이미지 파일명: 안심=good_bear, 검토=warning_bear, 주의=bad_bear
const BEAR_ICON_FILE: Record<ReviewLevel, string> = {
  SAFE: 'good_bear.png',
  CAUTION: 'warning_bear.png',
  REVIEW: 'bad_bear.png',
};

function getBearIconUrl(fileName: string) {
  return typeof chrome !== 'undefined' && chrome.runtime?.getURL
    ? chrome.runtime.getURL(`icons/${fileName}`)
    : `/icons/${fileName}`;
}

export function MoodFace({ level, size = 96 }: MoodFaceProps) {
  const src = getBearIconUrl(BEAR_ICON_FILE[level]);

  return (
    <img
      src={src}
      alt={`현재 위험도: ${level}`}
      width={size}
      height={size}
      style={{ width: size, height: size, objectFit: 'contain', display: 'block' }}
    />
  );
}

export default MoodFace;
