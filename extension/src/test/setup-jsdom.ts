// jsdom은 레이아웃 엔진이 없어서 innerText를 구현하지 않는다(jsdom/jsdom#1245).
// page-extractor.ts는 innerText로 "화면에 실제로 보이는 텍스트"를 읽으므로,
// 테스트에서는 textContent를 대역으로 써서 최소한의 동작을 재현한다.
if (!("innerText" in globalThis.HTMLElement.prototype)) {
  Object.defineProperty(globalThis.HTMLElement.prototype, "innerText", {
    get(this: HTMLElement) {
      return this.textContent ?? "";
    },
    set(this: HTMLElement, value: string) {
      this.textContent = value;
    },
    configurable: true,
  });
}
