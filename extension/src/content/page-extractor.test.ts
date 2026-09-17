import { describe, expect, it } from "vitest";
import { dedupeByContent, dedupeByUrl, normalizeText } from "./page-extractor";

describe("normalizeText", () => {
  it("공백만 다른 두 문자열을 같은 문자열로 정규화한다", () => {
    const a = normalizeText("국내유일,   소포리코사이드\n함유");
    const b = normalizeText("국내유일, 소포리코사이드 함유  ");
    expect(a).toBe(b);
  });

  it("특수문자·이모지는 그대로 둔다", () => {
    expect(normalizeText("★국내유일★ 소포리코사이드 100%!!")).toBe(
      "★국내유일★ 소포리코사이드 100%!!",
    );
  });
});

describe("dedupeByContent", () => {
  it("완전히 동일한 텍스트 3개가 1개로 줄어든다", () => {
    const texts = [
      { content: "국내유일, 소포리코사이드 함유", selector: "#banner" },
      { content: "국내유일, 소포리코사이드 함유", selector: "#summary" },
      { content: "국내유일, 소포리코사이드 함유", selector: "#popup" },
    ];

    const result = dedupeByContent(texts);

    expect(result).toHaveLength(1);
    expect(result[0].selector).toBe("#banner"); // 첫 번째 것만 유지
  });

  it("공백 차이만 있는 텍스트도 normalizeText를 거치면 같은 텍스트로 인식된다", () => {
    const texts = [
      { content: normalizeText("국내유일,   소포리코사이드\n함유"), selector: "#banner" },
      { content: normalizeText("국내유일, 소포리코사이드 함유  "), selector: "#summary" },
    ];

    const result = dedupeByContent(texts);

    expect(result).toHaveLength(1);
  });

  it("특수문자·이모지가 다르면 별개 텍스트로 유지한다", () => {
    const texts = [
      { content: "국내유일 소포리코사이드", selector: "#a" },
      { content: "★국내유일★ 소포리코사이드", selector: "#b" },
    ];

    const result = dedupeByContent(texts);

    expect(result).toHaveLength(2);
  });

  it("서로 다른 텍스트는 전부 유지한다", () => {
    const texts = [
      { content: "텍스트 A", selector: "#a" },
      { content: "텍스트 B", selector: "#b" },
    ];

    expect(dedupeByContent(texts)).toHaveLength(2);
  });
});

describe("dedupeByUrl", () => {
  it("완전히 동일한 이미지 URL 3개가 1개로 줄어든다", () => {
    const images = [
      { url: "https://example.com/img.jpg", alt: "배너" },
      { url: "https://example.com/img.jpg", alt: "상품간략설명" },
      { url: "https://example.com/img.jpg", alt: "팝업" },
    ];

    const result = dedupeByUrl(images);

    expect(result).toHaveLength(1);
    expect(result[0].alt).toBe("배너"); // 첫 번째 것만 유지
  });

  it("URL이 다르면 별개 이미지로 유지한다", () => {
    const images = [
      { url: "https://example.com/a.jpg", alt: null },
      { url: "https://example.com/b.jpg", alt: null },
    ];

    expect(dedupeByUrl(images)).toHaveLength(2);
  });
});
