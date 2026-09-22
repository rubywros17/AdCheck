package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 429 응답 본문에서 대기 시간과 한도 종류(분당/일일)를 제대로 뽑아내는지 검증한다.
 * 본문 예시는 실제 운영 로그에서 그대로 가져온 것이다(분당 15회 한도 초과 시 Gemini가
 * {@code retryDelay}로 몇 초 뒤에 다시 오라고 알려준다). 일일 한도(하루 500회)는 겉보기엔
 * 똑같은 429·같은 형태의 {@code retryDelay}를 주지만 실제로는 재시도해도 안 풀려서,
 * {@code quotaId}로 미리 구분해야 한다(2026-09-22 추가 — 재시도만 하다 실패하고 원인도
 * 못 밝히던 문제를 실측으로 확인해서 고쳤다).
 */
class GeminiClientRetryDelayTest {

    private static final String REAL_429_BODY = """
            {
              "error": {
                "code": 429,
                "message": "You exceeded your current quota... Please retry in 6.219895178s.",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [ { "quotaValue": "15" } ]
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "6s"
                  }
                ]
              }
            }
            """;

    @Test
    void 서버가_알려준_retryDelay를_사용하고_여유를_더한다() {
        long waitMs = GeminiClient.retryDelayMillis(REAL_429_BODY, 1);

        // 6초 + 경계에서 또 걸리지 않도록 더하는 여유 500ms
        assertThat(waitMs).isEqualTo(6_500);
    }

    @Test
    void retryDelay가_없으면_지수_백오프로_물러난다() {
        assertThat(GeminiClient.retryDelayMillis("{\"error\":{\"code\":429}}", 1)).isEqualTo(2_000);
        assertThat(GeminiClient.retryDelayMillis("{\"error\":{\"code\":429}}", 2)).isEqualTo(4_000);
        assertThat(GeminiClient.retryDelayMillis(null, 3)).isEqualTo(8_000);
    }

    @Test
    void 소수점_초도_처리한다() {
        assertThat(GeminiClient.retryDelayMillis("{\"retryDelay\": \"1.5s\"}", 1)).isEqualTo(2_000);
    }

    // 실제로 겪었던 일일 한도 429 본문 형태 — quotaId가 "PerDay"를 포함한다는 점만 분당
    // 한도(quotaId에 "PerMinute")와 다르고, 나머지 구조(status/details)는 동일하다.
    private static final String REAL_DAILY_QUOTA_429_BODY = """
            {
              "error": {
                "code": 429,
                "message": "You exceeded your current quota...",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                    "violations": [
                      { "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier", "quotaValue": "500" }
                    ]
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "59s"
                  }
                ]
              }
            }
            """;

    @Test
    void 일일_한도_429는_quotaId의_PerDay로_구분된다() {
        assertThat(GeminiClient.isDailyQuotaExceeded(REAL_DAILY_QUOTA_429_BODY)).isTrue();
    }

    @Test
    void 분당_한도_429는_일일_한도로_오판하지_않는다() {
        assertThat(GeminiClient.isDailyQuotaExceeded(REAL_429_BODY)).isFalse();
    }

    @Test
    void 본문이_없으면_일일_한도로_오판하지_않는다() {
        assertThat(GeminiClient.isDailyQuotaExceeded(null)).isFalse();
    }
}
