package com.adcheck.analysis.service;

/**
 * Gemini 무료 티어의 <b>일일</b> 요청 한도(예: {@code GenerateRequestsPerDayPerProjectPerModel-
 * FreeTier}, 하루 500회)를 초과했을 때 던진다. 분당 한도(15회)와 같은 HTTP 429이지만 회복
 * 방식이 다르다 — 분당 한도는 몇 초~몇십 초 재시도하면 풀리지만, 일일 한도는 하루가 지나야만
 * 풀려서 재시도가 무의미하다({@code GeminiClient}는 이 예외를 잡으면 재시도하지 않는다).
 */
public class GeminiDailyQuotaExceededException extends RuntimeException {

    public GeminiDailyQuotaExceededException(String model, Throwable cause) {
        super("Gemini 무료 티어 일일 호출 한도를 초과했습니다(모델: " + model
                + ") — 분당 한도와 달리 재시도로 풀리지 않으며, 하루가 지나야 복구됩니다.", cause);
    }
}
