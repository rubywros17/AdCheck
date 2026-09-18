package com.adcheck.analysis.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 실측에서 상세페이지 이미지 47장을 OCR했는데 추출 텍스트가 2,689자뿐이었다(17장은 0자).
 * 그 결과 Claim이 2~3건밖에 안 나왔다 — 상세페이지 광고 문구가 그것뿐일 리는 없어 보인다.
 *
 * <p>가설: 한 호출에 이미지를 여러 장 넣으면 모델이 각 이미지를 끝까지 전사하지 않고
 * 대충 요약하거나 건너뛴다. 같은 이미지를 <b>한 장씩 따로</b> OCR했을 때와 <b>여러 장 묶어서</b>
 * OCR했을 때 추출 글자 수를 비교해서 확인한다.
 *
 * <p>GEMINI_API_KEY 없으면 스킵. 호출 6회(개별 5 + 배치 1).
 */
class GeminiOcrChunkSizeExperimentTest {

    /** 실제 상품페이지(arucheum 양베진)의 상세 이미지 중 용량이 큰 것들 — 광고 문구가 들어있을 가능성이 높다. */
    private static final List<String> CONTENT_IMAGES = List.of(
            "https://arucheum.co.kr/web/upload/NNEditor/20250401/a2d74d1bce311980a1e401604f0cc1fb.jpg",
            "https://arucheum.co.kr/web/upload/NNEditor/20251218/e2043dee1e9ef1cbc227819a1cc9c8a9.jpg",
            "https://arucheum.co.kr/web/upload/NNEditor/20240102/be35cd471ebd71d819719efe04e181c4.jpg",
            "https://arucheum.co.kr/web/upload/NNEditor/20260107/59db0350937f1d749f95d8ec9a2a11ea.jpg",
            "https://arucheum.co.kr/web/upload/NNEditor/20251218/6813825417e0ecb20831c253dd55a145.jpg");

    @Test
    void 이미지를_한_장씩_OCR할_때와_묶어서_OCR할_때_추출량을_비교한다() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY 미설정 - 스킵");

        GeminiOcrService service = new GeminiOcrService(new GeminiClient(apiKey, "gemini-3.5-flash-lite"));

        System.out.println("=== 한 장씩 개별 OCR (5회 호출) ===");
        int individualTotal = 0;
        for (String imageUrl : CONTENT_IMAGES) {
            long startedAt = System.currentTimeMillis();
            String text = service.extractTexts(List.of(imageUrl)).getOrDefault(imageUrl, "");
            individualTotal += text.length();
            System.out.printf("  %-60s %5d자 (%dms)%n",
                    imageUrl.substring(imageUrl.lastIndexOf('/') + 1), text.length(),
                    System.currentTimeMillis() - startedAt);
            System.out.println("    미리보기: " + preview(text));
            sleep(4500);
        }

        System.out.println();
        System.out.println("=== 5장을 한 호출로 묶어서 OCR (1회 호출) ===");
        long batchStartedAt = System.currentTimeMillis();
        Map<String, String> batched = service.extractTexts(CONTENT_IMAGES);
        long batchElapsed = System.currentTimeMillis() - batchStartedAt;
        int batchTotal = batched.values().stream().mapToInt(String::length).sum();
        batched.forEach((url, text) -> {
            System.out.printf("  %-60s %5d자%n", url.substring(url.lastIndexOf('/') + 1), text.length());
            System.out.println("    미리보기: " + preview(text));
        });

        System.out.println();
        System.out.printf("개별 합계: %d자 / 배치 합계: %d자 (배치가 개별의 %.0f%%), 배치 소요 %dms%n",
                individualTotal, batchTotal,
                individualTotal == 0 ? 0 : 100.0 * batchTotal / individualTotal, batchElapsed);
    }

    private static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 100 ? flat : flat.substring(0, 100) + "...";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
