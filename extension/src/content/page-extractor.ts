import { marketplaceKind, marketplaceFrameSource, prepareMarketplaceDetail } from "./marketplace-detail";
import type { PageEvidence, PageImageEvidence, PageTextEvidence } from "../types/evidence";
import { createSelector } from "./selector";

export const MIN_TEXT_LENGTH = 8;
export const MAX_TEXT_LENGTH = 1_000;
export const MAX_TEXT_EVIDENCE_COUNT = 300;
export const MAX_IMAGE_EVIDENCE_COUNT = 60;

const TEXT_ELEMENT_SELECTOR = "h1, h2, h3, h4, h5, h6, p, span, li, strong, em, td, th";

// Prefer a platform's well-known product-description root, then fall back to
// semantic class/id names commonly used by independent stores. Images are
// never collected from the whole document: doing so fills the result with
// recommendation cards, logos and payment badges before the real detail art.
const DETAIL_CONTAINER_SELECTORS = [
  ".se-main-container", // Naver SmartEditor
  "#prdDetail",
  "#prdDetailContent",
  ".xans-product-additional",
  "#productDetail",
  "#product-detail",
  ".product-detail",
  ".product-description",
  ".detail-content",
  "[itemprop='description']",
] as const;

const DETAIL_CONTAINER_PATTERN =
  /(?:^|[\s_-])(product|prd)?[\s_-]*(detail|description|contents?)(?:$|[\s_-])|상품\s*상세|상세\s*(설명|정보)/i;
const EXCLUDED_SECTION_PATTERN =
  /recommend|related|relation|review|testimonial|feedback|comment|qna|question|recent|history|rating|best[\s_-]*item|other[\s_-]*product|추천\s*상품|상품\s*추천|연관\s*상품|관련\s*상품|포토\s*리뷰|리뷰|후기|구매평/i;
// notice/banner류는 로고·아이콘과 달리 판매자마다 이름 붙이는 방식이 제각각이라 완전히
// 걸러내진 못한다 — 실측(2026-09-22)에서 "notice_05.png" 같은 흔한 명명 사례를 잡기 위한
// 최소한의 패턴. 의미 없는 CDN 해시 파일명(예: img_20240512.jpg)은 이 패턴으로 못 걸러낸다.
const IRRELEVANT_IMAGE_PATTERN =
  /(?:^|[\/_\-.])(logo|icon|sprite|favicon|payment|kakao|callcenter|notice|banner)(?:[\/_\-.]|$)|고객센터|공지사항|배송안내|교환환불|반품안내|이용안내/i;
const INVISIBLE_CHAR_PATTERN = /[\u200B\u200C\u200D\uFEFF]/g;
const SEMANTIC_CONTAINER_SELECTOR = "section, article, div, td";
const IMAGE_SOURCE_ATTRIBUTES = [
  "src",
  // 카페24가 쓰는 지연 로딩 속성. 2026-09-23 실측(i-hi.co.kr/product_no=111)에서 상세 이미지
  // 24장이 전부 src 없이 ec-data-src만 갖고 있었고, 스크롤 대기가 끝날 때까지 브라우저가
  // src로 옮겨주지 못한 최하단 한 장이 빠졌다. 그게 하필 "상품정보고시"(원재료명·함량·
  // 품목보고번호) 이미지라, 원료 후보 0건 → 원료별 규칙 31개 미실행 → C05 보류 →
  // officialFunction·표시란 필터 무력화로 이어져 Claim 11건이 전부 "확인이 필요한 표현입니다"가
  // 됐다(3회 분석 내내 동일 재현). 속성에서 URL을 직접 읽으면 로딩 완료 여부와 무관해진다.
  "ec-data-src",
  "data-src",
  "data-original",
  "data-lazy-src",
  "data-original-src",
  "data-actualsrc",
  "data-url",
] as const;
const IMAGE_SRCSET_ATTRIBUTES = ["srcset", "data-srcset", "data-lazy-srcset"] as const;
const DETAIL_LABEL_PATTERN =
  /(?:상품\s*)?(?:상세\s*(?:정보|설명)|상품\s*정보)|\b(?:detail|description)\b/i;
const DETAIL_CONTROL_PATTERN =
  /(?:상품\s*)?(?:상세\s*(?:정보|설명)|상품\s*정보).*(?:더보기|펼쳐보기)/;
const LAZY_SCROLL_STEP_RATIO = 0.75;
const LAZY_SCROLL_WAIT_MS = 100;
const MAX_LAZY_SCROLL_STEPS = 20;

export async function extractPageEvidence(): Promise<PageEvidence> {
  if (marketplaceKind()) {
    const originalUrl = location.href;
    const originalY = scrollY;
    try {
      const root = await prepareMarketplaceDetail();
      const frameUrl = marketplaceFrameSource();
      let data: PageEvidence;
      if (frameUrl) {
        const response = await chrome.runtime.sendMessage({ type: "READ_MARKETPLACE_FRAME", url: frameUrl });
        if (!response?.ok) throw new Error(response?.error?.message ?? "판매자 상세 문서를 읽지 못했습니다.");
        data = response.data;
      } else {
        await loadLazyImagesThroughDetail(root);
        data = { pageUrl: location.href, pageTitle: document.title, productName: null,
          texts: extractTextEvidence(root), images: extractImageEvidence(root) };
      }
      if (location.href.split("#")[0] !== originalUrl.split("#")[0]) throw new Error("추출 중 상품이 변경되었습니다.");
      if (!data.images.length && !data.texts.length) throw new Error("판매자 상세 내용이 비어 있습니다. 상세설명을 확인해주세요.");
      return { ...data, pageUrl: location.href, pageTitle: document.title, productName: extractProductName() };
    } finally {
      if (location.href.split("#")[0] === originalUrl.split("#")[0]) window.scrollTo({top: originalY, behavior:"auto"});
    }
  }
  await prepareLazyDetailContent();

  return {
    pageUrl: window.location.href,
    pageTitle: truncateNullable(normalizeText(document.title), 300),
    productName: extractProductName(),
    texts: extractTextEvidence(),
    images: extractImageEvidence(),
  };
}

async function prepareLazyDetailContent(): Promise<void> {
  const originalScrollY = window.scrollY;
  const detailControl = findDetailControl();

  if (detailControl && isInteractiveControl(detailControl) && isCollapsedControl(detailControl)) {
    detailControl.scrollIntoView({ behavior: "auto", block: "center" });
    detailControl.click();
    await wait(350);
  }

  const detailContainer = findDetailContainer();
  if (detailContainer) {
    await loadLazyImagesThroughDetail(detailContainer);
  } else if (detailControl) {
    detailControl.scrollIntoView({ behavior: "auto", block: "center" });
    await wait(650);
  }

  window.scrollTo({ top: originalScrollY, behavior: "auto" });
}

async function loadLazyImagesThroughDetail(container: HTMLElement): Promise<void> {
  let stepCount = 0;
  let previousBottom = 0;

  while (stepCount < MAX_LAZY_SCROLL_STEPS) {
    const rect = container.getBoundingClientRect();
    const containerTop = window.scrollY + rect.top;
    const containerBottom = containerTop + Math.max(rect.height, container.scrollHeight);
    const detailHeight = Math.max(containerBottom - containerTop, 0);
    const fullCoverageStep = detailHeight / Math.max(MAX_LAZY_SCROLL_STEPS - 1, 1);
    const stepSize = Math.max(
      window.innerHeight * LAZY_SCROLL_STEP_RATIO,
      fullCoverageStep,
      400,
    );
    const targetY = Math.min(containerTop + stepCount * stepSize, containerBottom);

    window.scrollTo({ top: targetY, behavior: "auto" });
    await wait(LAZY_SCROLL_WAIT_MS);
    stepCount += 1;

    // Lazy rendering can extend the container while we scroll. Recalculate its
    // bottom on every step and stop only after reaching the current real end.
    const updatedRect = container.getBoundingClientRect();
    const updatedBottom = window.scrollY + updatedRect.top +
      Math.max(updatedRect.height, container.scrollHeight);
    if (targetY >= updatedBottom - window.innerHeight * 0.5) {
      if (Math.abs(updatedBottom - previousBottom) < 20) {
        break;
      }
      previousBottom = updatedBottom;
    }
  }

  await wait(250);
}

function findDetailControl(): HTMLElement | null {
  const controls = document.querySelectorAll<HTMLElement>(
    "button, a, [role='button'], [role='tab'], h2, h3, h4",
  );

  for (const control of controls) {
    const label = [
      normalizeText(control.innerText),
      normalizeText(control.getAttribute("aria-label") ?? ""),
      normalizeText(control.getAttribute("title") ?? ""),
    ].join(" ");
    if (DETAIL_CONTROL_PATTERN.test(label) || DETAIL_LABEL_PATTERN.test(label)) {
      return control;
    }
  }

  return null;
}

function isCollapsedControl(control: HTMLElement): boolean {
  const expanded = control.getAttribute("aria-expanded");
  const selected = control.getAttribute("aria-selected");
  const text = normalizeText(control.innerText);
  return expanded === "false" || selected === "false" || /더보기|펼쳐보기/.test(text);
}

function isInteractiveControl(control: HTMLElement): boolean {
  return (
    control.matches("button, a, [role='button'], [role='tab']") &&
    !control.matches(":disabled, [aria-disabled='true']")
  );
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function extractProductName(): string | null {
  const heading = document.querySelector<HTMLHeadingElement>("h1");
  const openGraphTitle = document.querySelector<HTMLMetaElement>('meta[property="og:title"]');
  const candidate =
    normalizeText(heading?.innerText ?? "") ||
    normalizeText(openGraphTitle?.content ?? "") ||
    normalizeText(document.title);
  return truncateNullable(candidate, 200);
}

export function extractTextEvidence(root: HTMLElement = document.body): PageTextEvidence[] {
  const seen = new Set<string>();
  const evidence: PageTextEvidence[] = [];
  const elements = root.querySelectorAll<HTMLElement>(TEXT_ELEMENT_SELECTOR);

  for (const element of elements) {
    if (evidence.length >= MAX_TEXT_EVIDENCE_COUNT) {
      break;
    }
    if (element.closest("script, style, noscript, template, nav, footer") || isExcludedText(element, root) || !isVisible(element)) {
      continue;
    }

    const content = normalizeText(cleanText(element)).slice(0, MAX_TEXT_LENGTH);
    if (content.length < MIN_TEXT_LENGTH || seen.has(content)) {
      continue;
    }

    seen.add(content);
    evidence.push({ content, selector: createSelector(element) });
  }
  return evidence;
}

export function extractImageEvidence(container: HTMLElement | null = findDetailContainer()): PageImageEvidence[] {
  if (!container) {
    console.info("[AdCheck] Product detail container was not found; skipping page-wide images");
    return [];
  }

  const seen = new Set<string>();
  const evidence: PageImageEvidence[] = [];
  // 관문별로 몇 장을 왜 버렸는지 센다. 이게 없을 때 "23장이 왔다"는 결과만 보여서,
  // 원료표가 담긴 이미지 한 장이 빠지는 원인을 추측으로 좁히다 세 번 틀렸다(2026-09-23).
  const skipped = { excludedSection: 0, noUrl: 0, duplicate: 0, irrelevant: 0 };
  const skippedDetail: Array<{ 이유: string; url: string; alt: string }> = [];

  for (const image of container.querySelectorAll<HTMLImageElement>("img")) {
    if (evidence.length >= MAX_IMAGE_EVIDENCE_COUNT) {
      break;
    }
    if (isInsideExcludedSection(image, container)) {
      skipped.excludedSection += 1;
      skippedDetail.push({ 이유: "제외섹션", url: image.getAttribute("ec-data-src") ?? image.src, alt: image.alt });
      continue;
    }

    const url = extractImageUrl(image);
    if (!url) {
      skipped.noUrl += 1;
      skippedDetail.push({ 이유: "URL없음", url: image.getAttribute("ec-data-src") ?? image.src, alt: image.alt });
      continue;
    }
    if (seen.has(url)) {
      skipped.duplicate += 1;
      continue;
    }
    if (isIrrelevantImage(image, url)) {
      skipped.irrelevant += 1;
      skippedDetail.push({ 이유: "부적합필터", url, alt: image.alt });
      continue;
    }

    seen.add(url);
    evidence.push({
      url,
      alt: truncateNullable(normalizeText(image.alt), 500),
    });
  }

  console.info("[AdCheck] 이미지 수집", {
    컨테이너: `${container.tagName}#${container.id || "-"}.${
      typeof container.className === "string" ? container.className.split(/\s+/)[0] : "-"}`,
    컨테이너_내_img: container.querySelectorAll("img").length,
    수집됨: evidence.length,
    건너뜀: skipped,
    건너뛴상세: skippedDetail,
  });
  return evidence;
}

function findDetailContainer(): HTMLElement | null {
  for (const selector of DETAIL_CONTAINER_SELECTORS) {
    const candidates = Array.from(document.querySelectorAll<HTMLElement>(selector)).filter(
      hasImageCandidate,
    );
    const best = selectBestDetailContainer(candidates);
    if (best) {
      return best;
    }
  }

  const controlledContainer = findControlledDetailContainer();
  if (controlledContainer) {
    return controlledContainer;
  }

  const semanticCandidates = Array.from(
    document.querySelectorAll<HTMLElement>(SEMANTIC_CONTAINER_SELECTOR),
  ).filter((element) => {
    const descriptor = getElementDescriptor(element);
    return (
      DETAIL_CONTAINER_PATTERN.test(descriptor) &&
      !EXCLUDED_SECTION_PATTERN.test(descriptor) &&
      hasImageCandidate(element)
    );
  });

  return selectBestDetailContainer(semanticCandidates);
}

function findControlledDetailContainer(): HTMLElement | null {
  const controls = document.querySelectorAll<HTMLElement>(
    "button[aria-controls], a[href*='#'], [role='tab'][aria-controls]",
  );

  for (const control of controls) {
    const label = [
      normalizeText(control.innerText),
      normalizeText(control.getAttribute("aria-label") ?? ""),
      normalizeText(control.getAttribute("title") ?? ""),
    ].join(" ");
    if (!DETAIL_LABEL_PATTERN.test(label)) {
      continue;
    }

    const targetId = getControlledTargetId(control);
    const target = targetId ? document.getElementById(targetId) : null;
    if (
      target instanceof HTMLElement &&
      hasImageCandidate(target) &&
      !EXCLUDED_SECTION_PATTERN.test(getElementDescriptor(target))
    ) {
      return target;
    }
  }

  return null;
}

function getControlledTargetId(control: HTMLElement): string | null {
  const ariaControls = control.getAttribute("aria-controls")?.trim();
  if (ariaControls) {
    return ariaControls.split(/\s+/)[0] ?? null;
  }

  const href = control.getAttribute("href");
  if (!href || !href.includes("#")) {
    return null;
  }

  try {
    return decodeURIComponent(new URL(href, document.baseURI).hash.slice(1)) || null;
  } catch {
    return null;
  }
}

function selectBestDetailContainer(candidates: HTMLElement[]): HTMLElement | null {
  let best: HTMLElement | null = null;
  let bestScore = -Infinity;

  for (const candidate of candidates) {
    const score = scoreDetailContainer(candidate);
    if (score > bestScore) {
      best = candidate;
      bestScore = score;
    }
  }

  return best;
}

function scoreDetailContainer(container: HTMLElement): number {
  const images = Array.from(container.querySelectorAll<HTMLImageElement>("img"));
  const linkedImageCount = images.filter((image) => image.closest("a")).length;
  const relevantImageCount = images.filter(
    (image) => !isInsideExcludedSection(image, container),
  ).length;
  const textLength = normalizeText(container.innerText).length;
  const linkRatio = images.length > 0 ? linkedImageCount / images.length : 1;

  return relevantImageCount * 200 + Math.min(textLength, 2_000) - linkRatio * 1_000;
}

function hasImageCandidate(element: HTMLElement): boolean {
  return element.querySelector("img") !== null;
}

function getElementDescriptor(element: Element): string {
  return [
    element.id,
    typeof element.className === "string" ? element.className : "",
    element.getAttribute("itemprop") ?? "",
    element.getAttribute("aria-label") ?? "",
    element.getAttribute("data-testid") ?? "",
    element.getAttribute("data-section") ?? "",
    element.getAttribute("data-type") ?? "",
    element.getAttribute("role") ?? "",
  ].join(" ");
}

function isInsideExcludedSection(image: HTMLImageElement, root: HTMLElement): boolean {
  let current: Element | null = image.parentElement;

  while (current) {
    if (EXCLUDED_SECTION_PATTERN.test(getElementDescriptor(current))) {
      return true;
    }
    if (current === root) {
      break;
    }
    current = current.parentElement;
  }

  return false;
}

function extractImageUrl(image: HTMLImageElement): string | null {
  for (const candidate of getImageUrlCandidates(image)) {
    const url = toHttpUrl(candidate);
    if (url) {
      return url;
    }
  }

  return null;
}

function getImageUrlCandidates(image: HTMLImageElement): string[] {
  const candidates = [image.currentSrc];

  for (const attribute of IMAGE_SOURCE_ATTRIBUTES) {
    candidates.push(image.getAttribute(attribute) ?? "");
  }
  for (const attribute of IMAGE_SRCSET_ATTRIBUTES) {
    candidates.push(...extractSrcsetUrls(image.getAttribute(attribute)));
  }

  const picture = image.closest("picture");
  if (picture) {
    for (const source of picture.querySelectorAll<HTMLSourceElement>("source")) {
      for (const attribute of IMAGE_SRCSET_ATTRIBUTES) {
        candidates.push(...extractSrcsetUrls(source.getAttribute(attribute)));
      }
    }
  }

  return Array.from(new Set(candidates.filter(Boolean)));
}

function extractSrcsetUrls(srcset: string | null): string[] {
  if (!srcset) {
    return [];
  }

  // Browsers conventionally list srcset entries from smaller to larger.
  // Reverse them so Vision receives the highest-resolution usable candidate.
  return srcset
    .split(",")
    .map((candidate) => candidate.trim().split(/\s+/)[0] ?? "")
    .filter(Boolean)
    .reverse();
}

function isIrrelevantImage(image: HTMLImageElement, url: string): boolean {
  // 로고·아이콘을 걸러내는 크기 검사. 단 <b>채택한 url이 실제로 화면에 그려진 경우에만</b>
  // 크기를 신뢰한다. 지연 로딩 중인 이미지는 1×1 투명 PNG(data URI) placeholder가 그려져
  // 있어 naturalWidth가 1로 읽히는데, 그걸 그대로 믿으면 실제 860×3043인 원료표까지
  // 로고 취급해 버린다.
  //
  // 실측(2026-09-23~27, i-hi.co.kr/product_no=111): 원재료명·함량·품목보고번호가 담긴
  // "상품정보고시" 이미지가 이 검사에서 버려져 원료 확정 0건 → 원료별 규칙 31개 미실행 →
  // C05 보류 → officialFunction·표시란 필터 무력화로 이어졌고, Claim이 전부 "확인이 필요한
  // 표현입니다." HIGH 카드가 됐다. 맨 아래까지 스크롤해 이미지가 로드된 뒤 분석하면 24장이
  // 전부 수집되는데, 스크롤 없이 누르면 23장이 되는 차이가 여기서 났다.
  //
  // image.complete로는 못 가른다 — placeholder 자체는 로드가 끝나 complete가 true다.
  // 로드를 기다리는 방법(대기 보강)도 시도했다 실패했는데, 애초에 우리는 URL만 필요하고
  // 백엔드가 직접 내려받으므로 브라우저 렌더링을 기다릴 이유가 없다.
  const renderedUrl = image.currentSrc || image.src;
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  if (renderedUrl === url && width > 0 && height > 0 && width < 80 && height < 80) {
    return true;
  }

  let decodedUrl = url;
  try {
    decodedUrl = decodeURIComponent(url);
  } catch {
    // Keep the original URL when a site exposes malformed percent encoding.
  }

  const descriptor = [decodedUrl, image.alt, getElementDescriptor(image)].join(" ");
  const irrelevantMatch = descriptor.match(IRRELEVANT_IMAGE_PATTERN);
  const excludedMatch = image.alt.match(EXCLUDED_SECTION_PATTERN);
  if (irrelevantMatch || excludedMatch) {
    // 어느 단어에 왜 걸렸는지 남긴다. 이게 없어서 URL·alt·class를 따로 확인하고도
    // 원인을 못 짚었다(2026-09-23~27, 상품정보고시 이미지 한 장이 계속 제외되던 건).
    console.info("[AdCheck] 부적합 이미지로 제외", {
      매칭단어: irrelevantMatch?.[0] ?? excludedMatch?.[0],
      어디서: irrelevantMatch ? "url+alt+속성" : "alt",
      descriptor: descriptor.slice(0, 400),
    });
    return true;
  }

  // Naver recommendation thumbnails commonly use linked alt text beginning
  // with '@'. Preserve the same image when it is genuinely part of the detail.
  return image.alt.trim().startsWith("@") && image.closest("a") !== null;
}

function toHttpUrl(rawUrl: string): string | null {
  if (!rawUrl || rawUrl.startsWith("data:")) {
    return null;
  }
  try {
    const url = new URL(rawUrl, document.baseURI);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : null;
  } catch {
    return null;
  }
}

function isVisible(element: HTMLElement): boolean {
  const style = window.getComputedStyle(element);
  return style.display !== "none" && style.visibility !== "hidden";
}

export function normalizeText(value: string): string {
  return value.replace(INVISIBLE_CHAR_PATTERN, "").replace(/\s+/g, " ").trim();
}

function truncateNullable(value: string, maxLength: number): string | null {
  return value ? value.slice(0, maxLength) : null;
}

// Prune excluded descendants too: a parent <li> or <span> can contain a review.
function isExcludedText(element: HTMLElement, root: HTMLElement): boolean {
  let node: HTMLElement | null = element;
  while (node) {
    const descriptor = getElementDescriptor(node).replace(/preview/gi, "");
    if (EXCLUDED_SECTION_PATTERN.test(descriptor) || /상품평|상품문의/.test(descriptor)) return true;
    if (node !== root && node.matches("section, article, div, ul, aside")) {
      const heading = Array.from(node.children).find(c => c.matches("h2,h3,h4,h5,[role='heading']"));
      if (heading && /^(추천상품|연관상품|함께.*상품|상품후기|상품평|리뷰|구매후기|상품문의)/.test(normalizeText(heading.textContent ?? "").replace(/\s/g,""))) return true;
    }
    if (node === root) break;
    node = node.parentElement;
  }
  return false;
}
function cleanText(element: HTMLElement): string {
  const copy = element.cloneNode(true) as HTMLElement;
  const originals = Array.from(element.querySelectorAll<HTMLElement>("*"));
  const copies = Array.from(copy.querySelectorAll<HTMLElement>("*"));
  originals.forEach((node, index) => {
    if (node.matches("script,style,noscript,template,nav,footer") || isExcludedText(node, element) || !isVisible(node)) copies[index].remove();
  });
  return copy.innerText;
}
export async function extractMarketplaceFrameEvidence(): Promise<PageEvidence> {
  await loadLazyImagesThroughDetail(document.body);
  return { pageUrl: location.href, pageTitle: document.title, productName: null,
    texts: extractTextEvidence(document.body), images: extractImageEvidence(document.body) };
}
