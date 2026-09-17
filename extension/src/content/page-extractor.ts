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
const IRRELEVANT_IMAGE_PATTERN =
  /(?:^|[\/_\-.])(logo|icon|sprite|favicon|payment|kakao|callcenter)(?:[\/_\-.]|$)|고객센터/i;
const INVISIBLE_CHAR_PATTERN = /[\u200B\u200C\u200D\uFEFF]/g;
const SEMANTIC_CONTAINER_SELECTOR = "section, article, div, td";
const IMAGE_SOURCE_ATTRIBUTES = [
  "src",
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

function extractTextEvidence(): PageTextEvidence[] {
  const seen = new Set<string>();
  const evidence: PageTextEvidence[] = [];
  const elements = document.querySelectorAll<HTMLElement>(TEXT_ELEMENT_SELECTOR);

  for (const element of elements) {
    if (evidence.length >= MAX_TEXT_EVIDENCE_COUNT) {
      break;
    }
    if (element.closest("script, style, noscript, template") || !isVisible(element)) {
      continue;
    }

    const content = normalizeText(element.innerText).slice(0, MAX_TEXT_LENGTH);
    if (content.length < MIN_TEXT_LENGTH || seen.has(content)) {
      continue;
    }

    seen.add(content);
    evidence.push({ content, selector: createSelector(element) });
  }
  return evidence;
}

function extractImageEvidence(): PageImageEvidence[] {
  const container = findDetailContainer();
  if (!container) {
    console.info("[AdCheck] Product detail container was not found; skipping page-wide images");
    return [];
  }

  const seen = new Set<string>();
  const evidence: PageImageEvidence[] = [];

  for (const image of container.querySelectorAll<HTMLImageElement>("img")) {
    if (evidence.length >= MAX_IMAGE_EVIDENCE_COUNT) {
      break;
    }
    if (isInsideExcludedSection(image, container)) {
      continue;
    }

    const url = extractImageUrl(image);
    if (!url || seen.has(url) || isIrrelevantImage(image, url)) {
      continue;
    }

    seen.add(url);
    evidence.push({
      url,
      alt: truncateNullable(normalizeText(image.alt), 500),
    });
  }
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
  const width = image.naturalWidth || image.width;
  const height = image.naturalHeight || image.height;
  if (width > 0 && height > 0 && width < 80 && height < 80) {
    return true;
  }

  let decodedUrl = url;
  try {
    decodedUrl = decodeURIComponent(url);
  } catch {
    // Keep the original URL when a site exposes malformed percent encoding.
  }

  const descriptor = [decodedUrl, image.alt, getElementDescriptor(image)].join(" ");
  if (
    IRRELEVANT_IMAGE_PATTERN.test(descriptor) ||
    EXCLUDED_SECTION_PATTERN.test(image.alt)
  ) {
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

function normalizeText(value: string): string {
  return value.replace(INVISIBLE_CHAR_PATTERN, "").replace(/\s+/g, " ").trim();
}

function truncateNullable(value: string, maxLength: number): string | null {
  return value ? value.slice(0, maxLength) : null;
}
