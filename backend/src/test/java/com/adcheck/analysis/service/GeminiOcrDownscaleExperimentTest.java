package com.adcheck.analysis.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OCR 전처리(가로 768px 리사이즈 + JPEG 재인코딩)가 실제로 시간을 줄이는지, 그리고 그 대가로
 * 글자를 잃지는 않는지 같은 이미지로 직접 비교한다.
 *
 * <p>배경: 실측에서 OCR이 분석 전체 시간의 44~65%를 차지했고, 이미지 "개수"보다 "해상도·용량"이
 * 지연을 좌우하는 정황이 있었다(같은 25~30장인데 6.97초 vs 23.7초 — 전자는 큰 이미지가 4MB
 * 필터로 빠진 경우). 한국 상품 상세페이지는 1000x3098처럼 세로로 긴 띠라 비전 모델 타일이
 * 8~10칸씩 잡히는데, 가로를 768px로 맞추면 가로 타일이 1칸으로 줄어 절반 이하가 된다.
 *
 * <p><b>실측 결과(2026-09-22, 2회 반복) — 가설 기각</b>:
 * <pre>
 *        전송 용량              시간                  추출 글자수
 * 1회차  2,400KB→424KB(18%)   3,508→4,052ms(116%)   917→783자(85%)
 * 2회차  2,400KB→424KB(18%)   4,004→3,099ms( 77%)   1,023→848자(83%)
 * </pre>
 * 시간은 116%/77%로 실행마다 뒤집혀 이득이 확인되지 않았고(Gemini 호출 자체의 편차 범위),
 * 추출 글자수는 두 번 다 15~17% 줄었다. Gemini가 서버 쪽에서 이미지를 자체 정규화해 토큰화하는
 * 것으로 보이며, 미리 줄이면 업로드 바이트만 아끼고 화질 손실만 남는다는 해석이다. 그래서
 * {@code GeminiOcrService}는 이 전처리를 파이프라인에 넣지 않는다.
 *
 * <p>다만 표본 이미지가 가로 860px라 768px와 차이가 작았다 — 원본이 2000px 이상인 페이지만
 * 모아서 같은 실험을 하면 결론이 달라질 수 있어, 그때 다시 돌려보라고 이 테스트를 남겨둔다.
 *
 * <p>GEMINI_API_KEY 없으면 스킵. Gemini 호출 2회(원본 배치 1 + 리사이즈 배치 1)를 쓴다.
 */
class GeminiOcrDownscaleExperimentTest {

    /** 실제 상품페이지 상세 이미지 — 세로로 긴 전형적인 한국 상세페이지 형태. */
    private static final List<String> CONTENT_IMAGES = List.of(
            "https://arucheum.co.kr/web/upload/NNEditor/20250401/a2d74d1bce311980a1e401604f0cc1fb.jpg",
            "https://arucheum.co.kr/web/upload/NNEditor/20251218/e2043dee1e9ef1cbc227819a1cc9c8a9.jpg",
            "https://arucheum.co.kr/web/upload/NNEditor/20240102/be35cd471ebd71d819719efe04e181c4.jpg",
            "https://arucheum.co.kr/web/upload/NNEditor/20260107/59db0350937f1d749f95d8ec9a2a11ea.jpg",
            "https://arucheum.co.kr/web/upload/NNEditor/20251218/6813825417e0ecb20831c253dd55a145.jpg");

    @Test
    void 원본과_리사이즈본의_OCR_시간과_추출량을_비교한다() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiClient client = new GeminiClient(apiKey, "gemini-3.5-flash-lite");

        List<byte[]> originals = new ArrayList<>();
        for (String url : CONTENT_IMAGES) {
            originals.add(URI.create(url).toURL().openStream().readAllBytes());
        }

        List<GeminiClient.ImageInput> originalInputs = new ArrayList<>();
        List<GeminiClient.ImageInput> resizedInputs = new ArrayList<>();
        long originalBytes = 0;
        long resizedBytes = 0;
        for (int i = 0; i < originals.size(); i++) {
            byte[] raw = originals.get(i);
            byte[] resized = GeminiOcrService.downscaleForOcr(raw, CONTENT_IMAGES.get(i));
            originalBytes += raw.length;
            resizedBytes += resized.length;
            originalInputs.add(new GeminiClient.ImageInput("image/jpeg", Base64.getEncoder().encodeToString(raw)));
            resizedInputs.add(new GeminiClient.ImageInput("image/jpeg", Base64.getEncoder().encodeToString(resized)));
        }

        System.out.printf("전송 용량: 원본 %,dKB → 리사이즈 %,dKB (%.0f%%)%n",
                originalBytes / 1024, resizedBytes / 1024, 100.0 * resizedBytes / originalBytes);

        String prompt = "이미지에 있는 텍스트를 그대로 모두 추출하세요. 이미지별로 순서대로, 각 이미지 사이는 줄바꿈으로 구분하세요.";

        long originalStartedAt = System.currentTimeMillis();
        String originalText = client.generate(prompt, originalInputs, false);
        long originalElapsed = System.currentTimeMillis() - originalStartedAt;

        sleep(5_000); // 분당 한도 여유

        long resizedStartedAt = System.currentTimeMillis();
        String resizedText = client.generate(prompt, resizedInputs, false);
        long resizedElapsed = System.currentTimeMillis() - resizedStartedAt;

        System.out.printf("원본  : %,6dms, 추출 %,5d자%n", originalElapsed, originalText.length());
        System.out.printf("리사이즈: %,6dms, 추출 %,5d자%n", resizedElapsed, resizedText.length());
        System.out.printf("시간 %.0f%%, 추출량 %.0f%%%n",
                100.0 * resizedElapsed / originalElapsed,
                originalText.isEmpty() ? 0 : 100.0 * resizedText.length() / originalText.length());
        System.out.println();
        System.out.println("=== 리사이즈본 추출 내용 ===");
        System.out.println(resizedText);

        assertThat(resizedBytes).isLessThan(originalBytes);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
