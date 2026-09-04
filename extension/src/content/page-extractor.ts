import type { PageEvidence, PageImageEvidence, PageTextEvidence } from "../types/evidence";
import { createSelector } from "./selector";

export const MIN_TEXT_LENGTH = 8;
export const MAX_TEXT_LENGTH = 1_000;
export const MAX_TEXT_EVIDENCE_COUNT = 300;
export const MAX_IMAGE_EVIDENCE_COUNT = 50;

const TEXT_ELEMENT_SELECTOR = "h1, h2, h3, h4, h5, h6, p, span, li, strong, em, td, th";

export function extractPageEvidence(): PageEvidence {
  return {
    pageUrl: window.location.href,
    pageTitle: truncateNullable(normalizeText(document.title), 300),
    productName: extractProductName(),
    texts: extractTextEvidence(),
    images: extractImageEvidence(),
  };
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
  const seen = new Set<string>();
  const evidence: PageImageEvidence[] = [];

  for (const image of document.querySelectorAll<HTMLImageElement>("img")) {
    if (evidence.length >= MAX_IMAGE_EVIDENCE_COUNT) {
      break;
    }
    const rawUrl = image.currentSrc || image.getAttribute("src") || "";
    const url = toHttpUrl(rawUrl);
    if (!url || seen.has(url)) {
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
  return value.replace(/\s+/g, " ").trim();
}

function truncateNullable(value: string, maxLength: number): string | null {
  return value ? value.slice(0, maxLength) : null;
}
