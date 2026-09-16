import type { PageEvidence } from "../types/evidence";

const STORAGE_KEY = "adcheckExtractionTestRecords";
const MAX_STORED_RECORDS = 30;

export interface ExtractionTestRecord {
  platform: string;
  url: string;
  productName: string | null;
  textCount: number;
  textDetails: string[];
  imageCount: number;
  imageDetails: string[];
  elapsedSeconds: number;
  testedAt: string;
}

export async function recordExtractionTest(
  evidence: PageEvidence,
  elapsedMilliseconds: number,
): Promise<void> {
  const records = await getExtractionTestRecords();
  const record: ExtractionTestRecord = {
    platform: detectPlatform(evidence.pageUrl),
    url: evidence.pageUrl,
    productName: evidence.productName,
    textCount: evidence.texts.length,
    textDetails: evidence.texts.map(({ content, selector }) =>
      selector ? `[${selector}] ${content}` : content,
    ),
    imageCount: evidence.images.length,
    imageDetails: evidence.images.map(({ url, alt }) =>
      alt ? `[${alt}] ${url}` : url,
    ),
    elapsedSeconds: Number((elapsedMilliseconds / 1_000).toFixed(3)),
    testedAt: new Date().toISOString(),
  };

  await chrome.storage.local.set({
    [STORAGE_KEY]: [...records, record].slice(-MAX_STORED_RECORDS),
  });
}

export async function getExtractionTestRecords(): Promise<ExtractionTestRecord[]> {
  const stored = await chrome.storage.local.get(STORAGE_KEY);
  const records: unknown = stored[STORAGE_KEY];
  return Array.isArray(records) ? records.filter(isExtractionTestRecord) : [];
}

export async function clearExtractionTestRecords(): Promise<void> {
  await chrome.storage.local.remove(STORAGE_KEY);
}

export async function exportExtractionTestRecords(): Promise<number> {
  const records = await getExtractionTestRecords();
  if (records.length === 0) {
    return 0;
  }

  const header = [
    "플랫폼",
    "url",
    "제품명",
    "텍스트 확보 건수",
    "텍스트 상세내역",
    "이미지 확보 건수",
    "이미지 상세내역",
    "소요시간(초)",
    "테스트 시각",
  ];
  const rows = records.map((record) => [
    record.platform,
    record.url,
    record.productName ?? "",
    record.textCount,
    record.textDetails.join("\n"),
    record.imageCount,
    record.imageDetails.join("\n"),
    record.elapsedSeconds,
    record.testedAt,
  ]);
  const csv = [header, ...rows]
    .map((row) => row.map(toCsvCell).join(","))
    .join("\r\n");
  const date = new Date().toISOString().slice(0, 10);
  const url = `data:text/csv;charset=utf-8,${encodeURIComponent(`\uFEFF${csv}`)}`;

  await chrome.downloads.download({
    url,
    filename: `adcheck-extraction-tests-${date}.csv`,
    saveAs: true,
  });
  return records.length;
}

function detectPlatform(pageUrl: string): string {
  const hostname = new URL(pageUrl).hostname.toLowerCase();

  if (hostname === "brand.naver.com") return "네이버 브랜드스토어";
  if (hostname === "smartstore.naver.com") return "네이버 스마트스토어";
  if (hostname.includes("shopping.naver.com")) return "네이버 가격비교";
  if (hostname.includes("coupang.com")) return "쿠팡";
  if (hostname.includes("gmarket.co.kr")) return "G마켓";
  if (hostname.includes("iherb.com")) return "iHerb";
  if (hostname.includes("kshop.co.kr")) return "KT알파 쇼핑";
  if (
    hostname.includes("shinsegaetvshopping.com") ||
    hostname.includes("shinsegaemall.ssg.com")
  ) {
    return "신세계V";
  }

  return `자사몰(${hostname})`;
}

function toCsvCell(value: string | number): string {
  let text = String(value);
  if (/^[=+\-@]/.test(text)) {
    text = `'${text}`;
  }
  return `"${text.replace(/"/g, '""')}"`;
}

function isExtractionTestRecord(value: unknown): value is ExtractionTestRecord {
  return (
    typeof value === "object" &&
    value !== null &&
    "url" in value &&
    typeof value.url === "string" &&
    "textCount" in value &&
    typeof value.textCount === "number" &&
    "imageCount" in value &&
    typeof value.imageCount === "number"
  );
}
