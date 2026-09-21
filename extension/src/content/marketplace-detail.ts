// Keep marketplace handling isolated from the existing Naver/OliveYoung path.
export const EXTRACTION_BUILD = "2026-09-17.10";
export function marketplaceKind(): "elevenst" | "gmarket" | null {
  if (/^(www\.)?11st\.co\.kr$/.test(location.hostname) && /^\/products\/\d+/.test(location.pathname)) return "elevenst";
  if (location.hostname === "item.gmarket.co.kr" && /^\/item\/?$/i.test(location.pathname)) return "gmarket";
  return null;
}

// Self-contained: also executed by the worker in the top frame to validate requests.
export function marketplaceFrameSource(): string | null {
  let frames: HTMLIFrameElement[] = [];
  if (/^(www\.)?11st\.co\.kr$/.test(location.hostname) && /^\/products\/\d+/.test(location.pathname)) {
    frames = Array.from(document.querySelectorAll<HTMLIFrameElement>("#ifrmDesc iframe#prdDescIfrm"));
  } else if (location.hostname === "item.gmarket.co.kr" && /^\/item\/?$/i.test(location.pathname)) {
    frames = Array.from(document.querySelectorAll<HTMLIFrameElement>("iframe")).filter(frame =>
      frame.id === "detail1" || /^(상품\s*)?(상세\s*(정보|설명)|상품\s*설명)$/.test(frame.title.trim())
    );
  }
  return frames.length === 1 && /^https?:/.test(frames[0].src) ? frames[0].src : null;
}

export function marketplaceRoot(): HTMLElement | null {
  if (marketplaceKind() === "elevenst") return document.querySelector("#ifrmDesc");
  if (marketplaceKind() === "gmarket") {
    const frame = Array.from(document.querySelectorAll<HTMLIFrameElement>("iframe")).find(f => f.id === "detail1" || /^(상품\s*)?(상세\s*(정보|설명)|상품\s*설명)$/.test(f.title.trim()));
    return frame?.parentElement ?? document.querySelector("#vip-tab_detail .box__item-description, #vip-tab_detail .item_description, #vip-tab_detail .detailcont");
  }
  return null;
}

export async function prepareMarketplaceDetail(): Promise<HTMLElement> {
  const deadline = Date.now() + 12_000;
  const clicked = new Set<HTMLElement>();
  while (Date.now() < deadline) {
    const tab = marketplaceKind() === "elevenst"
      ? document.querySelector<HTMLElement>("#tabMenuDetail1[aria-controls='tabpanelDetail1'][aria-selected='false']")
      : null;
    const controls = tab ? [tab] : Array.from(document.querySelectorAll<HTMLElement>("button, a, [role='tab']"))
      .filter(c => /^(상품상세|상품상세정보|상세설명|상세정보더보기|상품상세더보기|상세설명펼쳐보기)$/.test((c.textContent ?? "").replace(/\s/g,"")));
    for (const c of controls) {
      if (clicked.has(c) || c.getAttribute("aria-expanded") === "true" || c.getAttribute("aria-selected") === "true" || c.matches(":disabled")) continue;
      const style = getComputedStyle(c);
      if (style.display === "none" || style.visibility === "hidden") continue;
      const href = c.getAttribute("href");
      if (href && !href.startsWith("#") && !href.startsWith("javascript:")) continue;
      clicked.add(c); c.click(); break;
    }
    const root = marketplaceRoot();
    root?.scrollIntoView({block:"start", behavior:"auto"});
    if (root && (!root.querySelector("iframe") || marketplaceFrameSource())) return root;
    await new Promise(resolve => setTimeout(resolve,250));
  }
  throw new Error("판매자 상품 상세정보를 불러오지 못했습니다. 상세설명을 펼친 뒤 다시 실행해주세요.");
}
