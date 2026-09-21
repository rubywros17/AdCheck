import { beforeEach, describe, expect, it } from "vitest";
import { extractImageEvidence, extractTextEvidence, normalizeText } from "./page-extractor";

beforeEach(() => {
  document.body.innerHTML = "";
});

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

describe("extractTextEvidence", () => {
  it("완전히 동일한 텍스트 3개가 1개로 줄어든다", () => {
    document.body.innerHTML = `
      <div id="root">
        <p id="a">국내유일, 소포리코사이드 함유</p>
        <p id="b">국내유일, 소포리코사이드 함유</p>
        <p id="c">국내유일, 소포리코사이드 함유</p>
      </div>
    `;
    const root = document.getElementById("root")!;

    const result = extractTextEvidence(root);

    expect(result).toHaveLength(1);
    expect(result[0].selector).toBe("#a"); // 첫 번째 것만 유지
  });

  it("공백 차이만 있는 텍스트도 정규화를 거치면 같은 텍스트로 인식된다", () => {
    document.body.innerHTML = `
      <div id="root">
        <p id="a">국내유일,   소포리코사이드\n함유</p>
        <p id="b">국내유일, 소포리코사이드 함유  </p>
      </div>
    `;
    const root = document.getElementById("root")!;

    const result = extractTextEvidence(root);

    expect(result).toHaveLength(1);
  });

  it("특수문자·이모지가 다르면 별개 텍스트로 유지한다", () => {
    document.body.innerHTML = `
      <div id="root">
        <p id="a">국내유일 소포리코사이드</p>
        <p id="b">★국내유일★ 소포리코사이드</p>
      </div>
    `;
    const root = document.getElementById("root")!;

    const result = extractTextEvidence(root);

    expect(result).toHaveLength(2);
  });

  it("서로 다른 텍스트는 전부 유지한다", () => {
    document.body.innerHTML = `
      <div id="root">
        <p id="a">텍스트 A입니다</p>
        <p id="b">텍스트 B입니다</p>
      </div>
    `;
    const root = document.getElementById("root")!;

    expect(extractTextEvidence(root)).toHaveLength(2);
  });

  it("리뷰·추천상품 섹션 안의 텍스트는 제외한다", () => {
    document.body.innerHTML = `
      <div id="root">
        <p id="a">간 건강에 도움을 줍니다</p>
        <section id="reviews">
          <h3>상품후기</h3>
          <p id="review-text">정말 효과가 좋아요 강력 추천합니다</p>
        </section>
      </div>
    `;
    const root = document.getElementById("root")!;

    const result = extractTextEvidence(root);

    expect(result.map((item) => item.selector)).not.toContain("#review-text");
    expect(result).toHaveLength(1);
    expect(result[0].selector).toBe("#a");
  });

  it("8자 미만의 짧은 텍스트는 제외한다", () => {
    document.body.innerHTML = `
      <div id="root">
        <p id="short">짧은 텍스트</p>
      </div>
    `;
    const root = document.getElementById("root")!;

    expect(extractTextEvidence(root)).toHaveLength(0);
  });
});

describe("extractImageEvidence", () => {
  it("완전히 동일한 이미지 URL 3개가 1개로 줄어든다", () => {
    document.body.innerHTML = `
      <div id="root">
        <img id="a" src="https://example.com/img.jpg" alt="배너" width="200" height="200" />
        <img id="b" src="https://example.com/img.jpg" alt="상품간략설명" width="200" height="200" />
        <img id="c" src="https://example.com/img.jpg" alt="팝업" width="200" height="200" />
      </div>
    `;
    const root = document.getElementById("root")!;

    const result = extractImageEvidence(root);

    expect(result).toHaveLength(1);
    expect(result[0].alt).toBe("배너"); // 첫 번째 것만 유지
  });

  it("URL이 다르면 별개 이미지로 유지한다", () => {
    document.body.innerHTML = `
      <div id="root">
        <img id="a" src="https://example.com/a.jpg" width="200" height="200" />
        <img id="b" src="https://example.com/b.jpg" width="200" height="200" />
      </div>
    `;
    const root = document.getElementById("root")!;

    expect(extractImageEvidence(root)).toHaveLength(2);
  });

  it("컨테이너가 없으면 빈 배열을 반환한다", () => {
    expect(extractImageEvidence(null)).toHaveLength(0);
  });
});
